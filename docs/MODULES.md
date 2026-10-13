# Every module in Kirinuki

This walks the Java code package by package: what each one is for, what each class does, and how they
fit together. Read `TOOLS.md` first if you want to understand the external tools; this document is about
the code that coordinates them.

The whole app is ~2,000 lines across 49 files in one package, `ai.dashaun.kirinuki`. It is a
**layered Spring Boot app** with a twist: instead of the usual `controller → service → repository` and
nothing else, there's a **pipeline** of stages driven by an orchestrator.

## The 10,000-foot view

```
HTTP request                          Background pipeline (virtual thread)
────────────                          ──────────────────────────────────
VideoController                       PipelineOrchestrator
   │                                     │ runs each PipelineStage in order:
   ▼                                     │
VideoService ──── starts ────────────▶  1 DownloadStage        → YtDlpClient
   │                                     2 MediaPreparationStage → FfmpegClient
   ▼                                     3 TranscriptionStage    → WhisperClient
VideoRepository                          4 CandidateGenerationStage → CandidateGenerator
   │                                     5 ScoreCandidatesStage  → CandidateScorer → ChatClient
   ▼                                     6 RenderClipsStage      → ClipRenderer → FfmpegClient
Postgres (status + metadata)             │
                                         ▼
                              StorageService (the filesystem "blackboard")
```

Two golden rules explain most design decisions:

1. **Postgres holds status and metadata; the filesystem holds the real data.** Each video is a
   directory of artifacts. The database just tracks *where in the pipeline* each video is.
2. **Every stage produces one artifact and is skipped if that artifact already exists.** This one rule
   gives you resume-after-crash, safe re-runs, and idempotency for free.

---

## `KirinukiApplication`

The entry point. `@SpringBootApplication` plus two annotations:

- `@ConfigurationPropertiesScan` — finds the `@ConfigurationProperties` records (below) and binds them
  from YAML.
- `@EnableResilientMethods` — turns on Spring Framework 7's `@Retryable` and `@ConcurrencyLimit`
  (used on the LLM client and the heavy stages).

---

## `config/` — typed configuration

Instead of scattering `@Value("${...}")` strings everywhere, all configuration is bound into **records**
at startup. If the YAML is malformed, the app fails to start with a clear message, rather than blowing
up later.

| Class | Binds | Notable |
|---|---|---|
| `KirinukiStorageProperties` | `kirinuki.storage.root` | Where the filesystem blackboard lives (`./storage`). |
| `KirinukiYtDlpProperties` | `kirinuki.yt-dlp.*` | The binary name and two timeouts (metadata, download). |
| `KirinukiPipelineProperties` | `kirinuki.pipeline.*` | The big one: nested records for `media`, `asr`, `candidates`, `render`, `scoring`. Every tunable knob lives here. |
| `OpenTelemetryLogbackInstaller` | — | Not config — a bean that wires the OpenTelemetry log appender into Logback at startup (see the observability note at the end). |

**Why `@DefaultValue` matters here:** each nested record is annotated `@DefaultValue`. Without it, if a
whole section is absent from the YAML, the record binds to `null` and you'd get a `NullPointerException`
the first time a stage read it. `@DefaultValue` tells Spring "build this record from its own defaults
even when the YAML is silent" — which is why the base `application.yml` has no `kirinuki.pipeline`
block at all; the profiles fill it in.

**One deliberate rule:** `CandidateScoreClient` uses Spring AI's portable `ChatOptions`, never
provider-specific option classes. The *model name* lives in config (`spring.ai.openai.chat.model`), so
switching models is a YAML edit. This is what "model-agnostic" means in practice.

---

## `common/` — cross-cutting plumbing

### The exception hierarchy — one advice, many statuses

The house convention: **one base exception that carries its own HTTP status**, so the
`@RestControllerAdvice` never grows past a single handler method.

`KirinukiException` holds an `HttpStatus` and a title. Every specific exception extends it and supplies
those in its constructor:

| Exception | Status | Thrown when |
|---|---|---|
| `InvalidVideoUrlException` | 400 | The URL isn't a recognisable YouTube link. |
| `DuplicateVideoException` | 409 | That YouTube id was already ingested. |
| `VideoNotFoundException` | 404 | No video row for that id. |
| `ArtifactNotFoundException` | 404 | The video exists but hasn't produced that artifact yet. |
| `VideoNotResumableException` | 409 | Tried to re-drive a video that's paused or finished. |
| `VideoDownloadException` | 502 | yt-dlp failed. |
| `ExternalToolException` | 502 | A subprocess exited non-zero or timed out. |
| `ToolNotAvailableException` | 500 | A binary isn't on `PATH`. **Never retried** — a missing tool is a deployment fault, not a transient one. |

`GlobalExceptionHandler` is then *one method*: catch `KirinukiException`, read its status and title,
return a `ProblemDetail` (the RFC 9457 standard error shape). Add a new failure type → add a subclass,
never touch the handler. That's the whole point of the pattern.

### `ProcessRunner` — the single door to external tools

Every subprocess (yt-dlp, ffmpeg, Whisper) runs through this one class. It does three non-obvious
things right:

1. **Drains stdout and stderr on virtual threads.** A subprocess writing lots of output will *block*
   if nobody reads its pipe (the OS pipe buffer fills up). Reading both streams concurrently prevents
   that deadlock — and lets the timeout actually fire.
2. **Enforces a timeout.** `waitFor(timeout)` kills a runaway process instead of hanging forever.
3. **Turns exit codes into typed exceptions.** Non-zero exit → `ExternalToolException` (retryable).
   Can't even start the binary → `ToolNotAvailableException` (not retryable).

It's also where the observability wrapper lives — every tool run becomes a `kirinuki.tool` span.

---

## `storage/StorageService` — the blackboard

The filesystem is the source of truth, and this class is the only thing that touches it. Everything is
keyed by video id: `storage/{videoId}/{artifact}`.

Key methods:

- `resolve(videoId, artifact)` — the path to an artifact (may not exist yet).
- `exists(videoId, artifact)` — is it there? This is the **skip-if-exists** check the whole pipeline
  relies on.
- `prepareFor(videoId, artifact)` — the path, with parent directories created.
- `temporaryFor` / `commit` / `discardTemporary` — the **atomic write** mechanism.

**The atomic write is subtle and important.** A stage writes to `audio.part.wav`, not `audio.wav`. Only
when the stage *succeeds* does the orchestrator `commit` — an atomic filesystem `move` to `audio.wav`.
Why: if ffmpeg is killed mid-write (timeout, crash), it leaves a *truncated* `audio.wav`. On restart,
`exists()` would see that file, believe the stage was done, skip it, and feed a broken audio file to
Whisper. Writing to a temp name and committing only on success means a killed process leaves a `.part`
file that `exists()` ignores.

One gotcha baked in: the `.part` marker goes *before* the extension (`audio.part.wav`, not
`audio.wav.part`) because ffmpeg and yt-dlp both pick their output format from the extension. We found
this by testing — the naive naming broke both tools.

---

## `pipeline/` — the engine

This is the heart of the app and the part that differs from a normal Spring service.

### `PipelineStatus`

An enum of the 12 states a video moves through, from `DOWNLOADING` to `PUBLISHED`. Each constant
carries one boolean: `isResumable()`. `READY_FOR_REVIEW` (the human pause) and `PUBLISHED` (terminal)
are **not** resumable; everything else is. This is what stops a restart from re-driving a finished
video, and it's a property of each constant rather than a hand-maintained list that could drift.

### `Artifacts`

Just the string constants for artifact filenames (`source.mp4`, `audio.wav`, `transcript.json`,
`candidates.json`, `scored.json`, `clips.json`, and the `clips` directory). Centralised so stages that
read each other's outputs don't hardcode strings.

### `PipelineStage` — the interface

The abstraction the whole engine turns on. Each stage declares:

- `artifact()` — the file it produces (the skip/resume marker).
- `status()` — the status while it runs.
- `run(video)` — do the work.
- `completedStatus()` — the status after (defaults to `status()`; only download and transcription
  override it because they transition to a *different* state on completion).

### `PipelineOrchestrator` — the driver

Holds just three things: the repository, the storage service, and `List<PipelineStage>`. Spring injects
**all** the stage beans into that list, ordered by their `@Order` annotation. The orchestrator knows
nothing about ffmpeg or Ollama — only "run these stages in order".

The core loop (`runStage`) is the same for every stage:

```
set status = stage.status()
if artifact does NOT exist:
    run the stage        (writes to a .part file)
    commit the artifact  (atomic move)
set status = stage.completedStatus()
```

That single loop is where skip-if-exists, atomic commit, and status transitions live — not copied into
six stages. Adding a stage is: write one class, annotate `@Order(n)`, done. The orchestrator doesn't
change.

Two more responsibilities:

- **`startAsync`** dispatches the pipeline onto a **virtual thread** so `POST /videos` returns
  immediately. An in-flight `Set<UUID>` guard stops the same video running twice at once.
- **`recordFailure`** — if any stage throws, the error message is saved to `video.lastError`, so
  `GET /videos/{id}` can tell you *why* a video stopped, instead of it just silently stalling.

Everything is wrapped in Micrometer `Observation`s, so one video = one trace with a span per stage.

### `PipelineRecovery` — resume after restart

On `ApplicationReadyEvent`, it finds every video whose status `isResumable()` **and** that still has a
missing artifact, and re-drives each. Because completed stages skip, a resumed video only redoes the
stage that was interrupted. It runs on a virtual thread with its own try/catch — a failure here must
never take down app startup (an exception escaping a ready-event listener aborts `SpringApplication.run`).

### `pipeline/stage/` — the six stages

Each is a thin adapter: it knows which artifact it owns, which status it represents, and which
collaborator does the real work. They're deliberately dumb — the smarts live in the collaborators.

| Order | Stage | Status | Delegates to | ConcurrencyLimit |
|---|---|---|---|---|
| 1 | `DownloadStage` | DOWNLOADING→UPLOADED | `YtDlpClient` | — |
| 2 | `MediaPreparationStage` | MEDIA_PREPARATION | `FfmpegClient` | **1** (CPU-bound) |
| 3 | `TranscriptionStage` | FEATURE_EXTRACTION→WAITING_FOR_FEATURES | `WhisperClient` | **1** (holds the model) |
| 4 | `CandidateGenerationStage` | CANDIDATE_GENERATION | `CandidateGenerator` | — |
| 5 | `ScoreCandidatesStage` | AI_ANALYSIS | `CandidateScorer` | **1** (holds the model) |
| 6 | `RenderClipsStage` | CLIP_RENDERING | `ClipRenderer` | **1** (CPU-bound) |

`@ConcurrencyLimit(1)` on the heavy stages means that even if recovery finds 20 stranded videos, they
transcribe/score/render **one at a time** rather than forking 20 ffmpegs or exhausting VRAM. It's on
the *stage bean*, not the orchestrator method, because the orchestrator calls stages through injected
references (so the AOP proxy applies) — calling a method on `this` would silently bypass it.

---

## `video/` — the HTTP slice

Classic `controller → service → repository`, plus the DTOs.

- **`VideoController`** — HTTP only. `POST /videos` (returns 202 + starts the pipeline),
  `GET /videos/{id}` (status + lastError), `POST /videos/{id}/advance` (re-drive), and the artifact
  endpoints (`/transcript`, `/candidates`, `/scored`, `/clips`, `/clips/{index}` for the MP4 download).
  It builds `ResponseEntity`s and nothing else — no business logic.
- **`VideoService`** — the business logic. `ingest` extracts the YouTube id (a regex covering `watch?v=`,
  `youtu.be`, `shorts`, `live`, `embed`), dedups, fetches metadata synchronously, saves the row at
  `DOWNLOADING`, and kicks off the pipeline. `advance` guards against re-driving a non-resumable video.
  `artifactPath`/`clipPath` resolve artifacts for download, throwing 404 if absent. **No Spring web
  imports** — it returns `Path`, and the controller wraps it in a `FileSystemResource`.
- **`Video`** — the JPA entity. The only class mapped to a database row. It never leaves the service
  layer.
- **`VideoRepository`** — a `ListCrudRepository`, plus `existsByYoutubeId` (dedup).
- **`IngestVideoRequest` / `VideoResponse` / `YouTubeMetadata`** — DTOs, all records. Request and
  response are separate types; the entity is never returned directly.
- **`YtDlpClient`** — wraps `ProcessRunner` for the two yt-dlp calls. `@Retryable` on `download` (one
  retry — a transient network blip), **not** on `fetchMetadata` (it's in the request path and a dead
  video fails identically every time).

---

## `media/` — the ffmpeg and whisper adapters

- **`FfmpegClient`** — three methods: `extractAudio` (the `-vn -ac 1 -ar 16000` command),
  `renderVertical` (the blur-fill filter graph), `thumbnail` (grab one frame). Each builds an argument
  list and hands it to `ProcessRunner`. The `verticalFilter` method assembles the filter-graph string
  from the `render` config (dimensions, zoom, blur).
- **`WhisperClient`** — runs `whisper-ctranslate2` via `ProcessRunner`, then renames the tool's
  `audio.json` output to `transcript.json`.

---

## `candidate/` — finding the moments (no AI)

This stage is **deterministic** — no model call. It turns the transcript into clip candidates.

- **`Word`** — a record: `{text, start, end}`. The atomic unit.
- **`TranscriptReader`** — parses `transcript.json` into a flat `List<Word>`. Shared by candidate
  generation *and* subtitle writing, which is why it's its own class.
- **`Candidate`** — a proposed clip window: id, start, end, word indices, text.
- **`CandidateGenerator`** — the algorithm. Whisper's *segments* break mid-sentence, so it first rebuilds
  real sentences by scanning for terminal punctuation (`. ? !`). Then, at each sentence boundary, it
  emits a **short** window (~20s) and a **long** window (~60s) — so the scorer can later judge whether a
  moment plays better tight or extended. If a long video would produce too many, it *thins evenly*
  across the whole transcript (keeping whole short/long pairs) rather than truncating — otherwise a
  1-hour video would only get candidates from its opening minutes.

---

## `scoring/` — the AI judgement

Where the LLM earns its keep. This is the milestone-defining stage: everything before it is plumbing to
get here.

- **`CandidateScore`** — the model's output, a record of *sub-scores* (hook, educationalValue, emotion,
  virality), suggested platforms, and a one-line reason. Note: **no overall score** —
  the model doesn't compute that. Has a `clamped()` method that pins every sub-score to 0–10, so a model
  returning `250` can't corrupt the ranking.
- **`CandidateScoreClient`** — the actual LLM call. Builds a `ChatClient` with the few-shot system
  prompt and the temperature from config, and `@Retryable`-scores one candidate, returning a typed
  `CandidateScore` via `.entity()`. The retry lives here (a separate bean) so the AOP proxy applies.
- **`ScoredCandidate`** — pairs a `Candidate` with its `CandidateScore` and the computed `overallScore`.
- **`CandidateScorer`** — the orchestration: score every candidate (isolating failures so one bad
  candidate doesn't sink the batch), compute each `overallScore` as a **weighted sum** of the sub-scores
  (weights from config), **drop anything below the configurable `scoring.min-score` floor** (default 0,
  set to 50 in the profiles), **dedup** overlapping windows (>50% overlap → keep the higher score), sort,
  and keep the top N. The floor means a weak video yields a few strong clips instead of a padded top-N;
  the dedup is what stops the short/long pairs from filling the output with the same moment twice.

---

## `render/` — producing the clips (no AI)

Deterministic again. Turns the top-N scored candidates into finished MP4s.

- **`Clip`** — a record describing one rendered clip in the manifest: index, timing, score, reason, and
  the video/thumbnail filenames.
- **`SubtitleWriter`** — filters transcript words to a clip's window, rebases their timestamps to
  clip-relative, groups ~5 words per caption line, and writes an ASS subtitle file. The styling (font,
  size, bottom margin) comes from the `render` config.
- **`ClipRenderer`** — for each scored candidate: write its subtitles, call `FfmpegClient.renderVertical`
  to produce the MP4, call `thumbnail` for the JPG, and collect a `Clip` entry. Writes the whole set as
  `clips.json` — the manifest the download endpoints serve.

---

## How a request flows, end to end

Putting it together, here's what happens when you `POST /videos`:

1. **`VideoController.ingest`** receives the URL, calls `VideoService.ingest`.
2. **`VideoService`** extracts the YouTube id, checks `VideoRepository.existsByYoutubeId` (dedup),
   calls `YtDlpClient.fetchMetadata` (synchronous — fails fast on a bad video), saves a `Video` row at
   `DOWNLOADING`, calls `PipelineOrchestrator.startAsync`, and returns `202`. **The HTTP request is now
   done** — everything below runs on a virtual thread.
3. **`PipelineOrchestrator`** runs the six stages in order. Each: set status, check artifact, run if
   missing, commit, advance status.
   - `DownloadStage` → `YtDlpClient` → `source.mp4`
   - `MediaPreparationStage` → `FfmpegClient` → `audio.wav`
   - `TranscriptionStage` → `WhisperClient` → `transcript.json`
   - `CandidateGenerationStage` → `CandidateGenerator` → `candidates.json`
   - `ScoreCandidatesStage` → `CandidateScorer` → `ChatClient`/Ollama → `scored.json`
   - `RenderClipsStage` → `ClipRenderer` → `FfmpegClient` → `clips/*.mp4` + `clips.json`
4. Throughout, **`StorageService`** is the only thing writing files, always via temp-then-commit.
   **Postgres** only ever holds the current status and the metadata.
5. You poll **`GET /videos/{id}`** to watch the status advance, then **`GET /videos/{id}/clips/{index}`**
   to download a finished short.

If the app crashes at step 3, **`PipelineRecovery`** restarts it from exactly the stage that was
interrupted, because every completed artifact still exists on disk.

---

## The observability layer (cross-cutting)

Not a package, but woven through: every stage and every tool run is wrapped in a Micrometer
`Observation`, and Spring AI auto-instruments the LLM calls. The result is that one video shows up in
Grafana (Tempo) as a single trace:

```
kirinuki.pipeline
├─ kirinuki.stage (DownloadStage) → kirinuki.tool (yt-dlp)
├─ kirinuki.stage (TranscriptionStage) → kirinuki.tool (whisper-ctranslate2)
├─ kirinuki.stage (ScoreCandidatesStage) → spring_ai chat_client × N
└─ kirinuki.stage (RenderClipsStage) → kirinuki.tool (ffmpeg) × N
```

plus timer metrics per stage and per tool, and `gen_ai_*` token/latency metrics from Spring AI. That's
how you answer "where did the minutes go" without reading logs.

---

## If you want to really learn it

Reading this tells you the shape. To make it *yours*, change one thing and trace the consequence:

1. **Add a trivial 7th stage.** Copy `CandidateGenerationStage`, annotate `@Order(7)`, have it write a
   `hello.txt` artifact. Watch it appear in the pipeline with zero orchestrator changes. That's the
   `PipelineStage` abstraction paying off, felt rather than read.
2. **Break the atomic write.** Temporarily make `MediaPreparationStage` write straight to `audio.wav`
   instead of the temp path, set the ffmpeg timeout to 2s so it's killed mid-write, then restart and
   watch the pipeline skip a broken file. Then revert and see it handled correctly.
3. **Retune the ranking.** Change `scoring.weights` (e.g. drop `hook` to 1, raise `educationalValue`)
   and re-drive a scored video. The clip order changes, and you never touched the prompt — that's the
   deterministic-aggregation decision, made concrete.

Each of those takes ten minutes and teaches more than re-reading the doc.
