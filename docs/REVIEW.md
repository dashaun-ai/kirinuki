# Kirinuki code review — findings and proposed changes

## Context

A review of the tree as of 2026-08-14 (`main` @ `7ef16dc`): what is amiss, what needs improvement,
with the reasoning and the evidence behind each call. No code was changed — this is the write-up plus
the fix list, ordered so the sequencing stays your call.

> **Status as of 2026-08-26.** This is a dated snapshot and the findings below are left as written.
> Seven have since been closed on `fix/bug-fixes`:
>
> | Finding | Closed by |
> |---|---|
> | A2 — `lastError` cleared in memory but not persisted | `676e095` |
> | A5 — `fanOut` keeps only the last failure | `676e095` — first failure thrown, rest suppressed |
> | B1 — the `sekisho.client.*` block is inert | `676e095` — block and dependency removed |
> | C4 — a REST advice serves the HTML dashboard | `87b5e59` — separate `DashboardExceptionHandler` |
> | D8 — `SubtitleWriter` emits malformed timestamps | `676e095` |
> | E3 — CLAUDE.md still describes Ollama | rewritten 2026-08-26 |
> | E4 — CLAUDE.md's inventory is stale | rewritten 2026-08-26 |
>
> **A6 is only half closed.** `676e095` fixed the seeding race (concurrent first loads no longer collide
> on the `unique (video_id, clip_index)` index), but `edit` / `decide` / `regenerate` are still
> read-modify-write across separate transactions, so two concurrent edits to one clip still lose one.
> That needs `@Version` on `ClipReview` and a `V4__` migration.
>
> **B2 is only half closed.** `candidates-per-request` is gone; `kirinuki.pipeline.media.frame-interval`
> is still set in both profiles and still binds to nothing.
>
> Everything else below is open, including A7 (seeding still happens on a `GET`).

Scope reviewed: all 80 Java files, `application*.yml`, the three Flyway migrations, the three
Thymeleaf templates, `pom.xml`, `docker-compose.yml`, `Dockerfile`, CI, and the three completed runs
sitting in `storage/`. The runs matter — two of the findings below are things that already happened
on this machine, not things that could happen.

---

## A. Correctness

### A1 — An empty result set sails through to READY_FOR_REVIEW

`CandidateScorer.score` guards `scored.isEmpty()` (CandidateScorer.java:47) *before* ranking, but
`rank()` then applies `min-score` and the top-N cut, and an empty `ranked` list is written out with no
complaint. Downstream, `RenderClipsStage` renders zero clips, `ContentGenerator` writes `[]`, and the
video lands on READY_FOR_REVIEW with a dashboard showing nothing and `lastError` null.

**Evidence:** `storage/ed94f058-cd07-45e5-8632-d4d39645d9f1/` — `candidates.json` has 34 entries,
`scored.json` is `[]`, `clips.json` is `[]`. Every candidate was scored successfully and then filtered
out by `scoring.min-score: 50` (application-local.yml:17), because the scoring prompt explicitly says
"Most candidates are average (3-5)" — a straight 5 across the board yields 50, so the cut sits exactly
at the model's own stated centre of mass. For comparison, `e8556bb1` survived with scores
`[61, 53, 52, 50, 50]`: three of its five clips are within 3 points of the cliff.

**Fix:** throw when `ranked.isEmpty() && !candidates.isEmpty()`, with the score distribution in the
message. Log the histogram and how many the `min-score` cut removed on every run. Separately, 50 is
too aggressive a default against this prompt — worth dropping to ~40 or re-calibrating the prompt.

### A2 — `lastError` is cleared in memory but not persisted

`PipelineOrchestrator.advance` calls `video.setLastError(null)` (PipelineOrchestrator.java:58) and
only saves at the *end* of the first stage. Transcription is a two-hour timeout on this box, so for
that whole window `GET /videos/{id}` and `processing.html:34` keep rendering "Stalled: …" with a Retry
button next to a pipeline that is running fine. Clicking Retry is a no-op (the `inFlight` guard
silently swallows it), which makes it look doubly broken.

**Fix:** save immediately after clearing.

### A3 — `BoundaryRefiner` can move a clip start *into* the clip

`nearestSilenceEndingNear` matches on `Math.abs(silence.end() - target)` (BoundaryRefiner.java:61), so
a silence that *ends after* the candidate start is a valid match; `Math.max(pause.start(), start -
leadIn)` (line 35) then returns a start later than the candidate's, clipping the first word.

**Evidence:** comparing `scored.json` to `clips.json`, 2 of 11 rendered clips start after their
candidate — `+0.02s` and `+0.14s` (video `5a09516b`, clips 1 and 4). Small, but it is the opening
word of a short-form clip, which is the one word that has to land.

**Fix:** only consider silences ending at or before `candidate.start() + tolerance`, and clamp the
result to `min(result, candidate.start())`.

### A4 — Candidate windows are not bounded by `max-duration`

`groups()` grows `shortEnd` until the window reaches `min-duration` with no upper check
(CandidateGenerator.java:126-133); `max-duration` is only consulted when *extending* to the long
window. One uninterrupted segment longer than 60s produces a candidate that exceeds the configured
maximum and is never rejected anywhere downstream.

**Evidence:** latent, not observed — the longest candidate across both runs is 59.6s, because dense
speech keeps segments short. It is unguarded, not correct.

**Fix:** discard (or split) a group whose short window already exceeds `max-duration`.

### A5 — `fanOut` keeps only the last failure

`PipelineOrchestrator.fanOut` (lines 85-96) overwrites `failure` on each iteration, so if two of the
three FEATURE_EXTRACTION stages fail, the first exception is discarded without even a log line — and
that stage's cause is what you'd want when whisper and ffmpeg fail together (usually the same root
cause, but you only see one).

**Fix:** keep the first and `addSuppressed` the rest, or log each as it arrives.

### A6 — `ReviewService` has no transaction boundary

`ensureSeeded` (ReviewService.java:164-177) is check-then-`saveAll` with no transaction. The dashboard
has a Refresh link and browsers double-submit; two concurrent loads both pass `existsByVideoId` and
the second `saveAll` hits `unique (video_id, clip_index)` (V3__clip_review.sql:9) → 500. `edit`,
`decide` and `regenerate` are read-modify-write on a detached entity with no `@Version`, so
concurrent edits silently lose one.

**Fix:** `@Transactional` on the mutating methods; make seeding idempotent (insert-if-absent per clip
index rather than all-or-nothing on `existsByVideoId`).

### A7 — Seeding review rows happens on a GET

`review()` writes to the database. It works, but a read endpoint that inserts is the kind of thing
that bites during a demo (double-tap the page, get a 500 from A6). Seeding belongs at the end of
`ContentGenerationStage`, where the content it derives from is produced.

### A8 — `@ConcurrencyLimit(1)` is per-bean, so it does not do what the fan-out needs

Three stages share status FEATURE_EXTRACTION (`TranscriptionStage`, `SceneExtractionStage`,
`AudioExtractionStage`), each annotated `@ConcurrencyLimit(1)` — but the limit is per bean-method, so
`fanOut` still starts whisper plus two ffmpeg passes simultaneously on a 12-core CPU-only box. The
annotation only serialises *across videos*, which is not the pressure you documented. `DownloadStage`
and `CandidateGenerationStage` have no limit at all, which makes the intent unreadable.

**Fix:** decide what it is for. Either a single shared permit for "heavy external tool" (one bean
holding a `Semaphore`, called from `ProcessRunner`), or drop the annotation from the stages where it
buys nothing. This is a design call, not a mechanical fix — flagging, not proposing.

### A9 — LLM calls have no timeout

`kirinuki.pipeline.scoring.timeout` is set in both profiles (2h local, 20m gpu) and **is never read** —
grep for `timeout()` across `src/main/java` returns only `asr`, `media` and `render`. Spring AI's
OpenAI client runs on the default `RestClient` with no read timeout configured, so a hung gateway
hangs the stage indefinitely. On this box a single scoring call legitimately takes minutes, so you
cannot tell "hung" from "slow" by watching.

**Fix:** either wire `scoring.timeout` into an explicit `RestClient` request factory, or delete the
property so it stops implying a control that doesn't exist. The first is the right one.

---

## B. Configuration

### B1 — The `sekisho.client.*` block is inert

`spring.ai.openai.api-key: placeholder-jwt-injected` (application.yml:32) is **intended** — the
gateway works that way. Confirmed with the author; not a finding.

What remains: application.yml:37-43 declares `sekisho.client.token-uri / client-id / client-secret /
scope` and `sekisho.timeout`, and nothing reads them. There is no
`@ConfigurationProperties(prefix = "sekisho")` anywhere, `grep -rni sekisho src/main/java` returns
nothing, and `git log -S sekisho` shows no such class was ever committed and later removed — the
client-credentials grant described in the `.envrc.example` comments has never existed in this repo.

Because nothing binds that prefix, the `${INKAN_TOKEN_URI}` / `${SEKISHO_CLIENT_ID}` /
`${SEKISHO_CLIENT_SECRET}` placeholders are never resolved (Spring resolves lazily, on access). So all
three environment variables can be unset and the app still starts and still reaches the gateway —
despite `.envrc.example:5-6` marking the secret **Required**. `DATABASE_URL` *is* genuinely required
because it is bound, which is what makes the other three read as required by association.

**Fix:** depends on where the JWT actually comes from. If a proxy or sidecar swaps the placeholder,
the block belongs in that component's config and should be deleted here along with the three `.envrc`
entries. If the app was meant to perform the grant itself, it is unimplemented — a
`KirinukiSekishoProperties` record plus a `RestClient` request interceptor caching the token until
expiry. Either way, config that binds to nothing should not sit in `application.yml` looking load-bearing.

### B2 — Dead profile keys that look like real tuning knobs

- `kirinuki.pipeline.media.frame-interval` — application-local.yml:8, application-gpu.yml:4
- `kirinuki.pipeline.scoring.candidates-per-request` — application-gpu.yml:12

Neither exists on `KirinukiPipelineProperties.Media` (only `binary`, `timeout`) or `.Scoring` (only
`topClips`, `minScore`, `temperature`, `timeout`, `weights`). Constructor binding ignores unknown keys,
so these are silent no-ops. `candidates-per-request` is especially misleading — it implies batched
scoring, and the code scores strictly one candidate per call.

**Fix:** delete all three lines.

### B3 — `kirinuki.storage.root` has no default

`KirinukiStorageProperties(Path root)` — a missing value NPEs inside `StorageService`'s constructor
with no indication of which property is at fault. `@DefaultValue("./storage")` costs one annotation.

---

## C. House conventions

### C1 — `@Slf4j` on a service, and a log line on every read

`VideoService` is the only class using Lombok's `@Slf4j` (VideoService.java:24); every other class
declares the logger by hand. Your global rules put Lombok on entities and complex POJOs only, never on
services. And line 61 logs at INFO on every `findById` — this is exactly the console flood CLAUDE.md
warns me about, coming from your own code, hit once per dashboard load.

**Fix:** delete the log line and the annotation.

### C2 — Transport concern inside a service

`ReviewService.toResponse` builds `"/videos/%s/clips/%d"` (line 204). URL shape is a controller
concern; the service should hand back the clip index and let the web layer form the link.

### C3 — Ordinal arithmetic on `PipelineStatus`

`DashboardController` compares `status.ordinal() < READY_FOR_REVIEW.ordinal()` (line 52) and derives
step state from ordinals (110-111); `PipelineStatus.next()` is ordinal-based too. Reordering or
inserting an enum constant silently changes both the UI and the pipeline's successor logic, with no
compile error. The `PIPELINE` list (line 102, declared between methods) and `label()` then encode the
same ordering a second and third time.

**Fix:** an explicit predicate on the enum (`isBeforeReview()`), and one source of display order —
either a label on the enum or the list, not both.

### C4 — A REST advice serves the HTML dashboard

`GlobalExceptionHandler` is `@RestControllerAdvice`, so its `ProblemDetail` is serialised as JSON for
`DashboardController` too. Post a duplicate URL to `/ingest` and the browser gets raw JSON instead of
the page re-rendered with an error.

**Fix:** a second `@ControllerAdvice` scoped to the dashboard package that returns an error view, or
handle `DuplicateVideoException` in the controller.

Note: validation failures are *not* broken by the catch-all `@ExceptionHandler(Exception.class)` —
Boot's `ProblemDetailsExceptionHandler` is ordered ahead of an unordered advice, so `@Valid` errors
still return 400. Worth stating explicitly so it doesn't get "fixed".

### C5 — Lombok on the form-binding classes is fine

`ClipEditForm` / `PlatformEditForm` use `@Getter/@Setter` against the "records for DTOs" rule, but
indexed binding of `platforms[i].title` needs mutable beans — records cannot do it. This is a correct
deviation; it should be written down in CLAUDE.md so the next review doesn't flag it.

---

## D. Media and rendering

### D1 — `renderVertical` buffers ffmpeg's entire progress log per clip

It is the only ffmpeg invocation without `-hide_banner -nostats` (FfmpegClient.java:95-106), so
`ProcessRunner.readFully` accumulates the whole progress stream into a String and discards it — once
per clip, on the slowest stage.

### D2 — `ProcessRunner` never logs the command

When ffmpeg fails you get an exit code and a stderr tail, and no way to reproduce the invocation by
hand. A debug-level log of the argv would have paid for itself several times over already.

### D3 — Unescaped subtitle path in the filtergraph

`subtitles=%s` (FfmpegClient.java:123). Safe for today's paths (`<root>/<uuid>/clips/clip-N.ass`), but
ffmpeg's filter syntax treats `:`, `'` and `\` as structure — the moment the storage root moves
somewhere with a colon in it, the graph breaks in a way that reads as an ffmpeg bug.

### D4 — `toUpperCase()` without a Locale

`SubtitleWriter.java:36`. Use `Locale.ROOT`.

### D5 — Re-rendering redoes finished work

`RenderClipsStage` is gated only on `clips.json`, so a retry re-renders every clip from scratch at
~1.8x realtime, including the ones already on disk.

**Fix:** skip a clip whose mp4 exists; clear `clips/` when the stage's temporary artifact is discarded.

### D6 — Clip mp4s bypass the `.part` commit scheme

The manifest is written to `clips.part.json` and committed atomically, but the mp4s are written
straight into the final `clips/` directory. A killed render leaves a truncated `clip-N.mp4` that
`GET /videos/{id}/clips/{n}` serves without complaint — the exact failure mode the `.part` scheme
exists to prevent, on the one artifact a human actually watches.

### D7 — The inline player is served as an attachment

`VideoController.clip` forces `Content-Disposition: attachment` (line 82), and `dashboard.html:41`
points a `<video src>` at that same URL. Serve `inline` and give the Download link its own flag.

### D8 — `SubtitleWriter` emits malformed timestamps

`timestamp()` (SubtitleWriter.java:73-77) computes `centis = Math.round((seconds - total) * 100)`. When
the fractional part is ≥ 0.995 that rounds to **100**, and `%02d` prints it as three digits — producing
`0:00:14.100`, which is not a valid ASS timestamp. The centiseconds field is two characters wide, so
libass reads `10` and drops the rest: the caption lands at 14.10s instead of 15.00s, roughly 0.9s off.

**Evidence:** already in shipped output, not latent —

```
storage/5a09516b.../clips/clip-5.ass:26  Dialogue: 0,0:00:13.96,0:00:14.100,...,and then it
storage/5a09516b.../clips/clip-5.ass:27  Dialogue: 0,0:00:14.100,0:00:16.06,...,will be reusable
storage/5a09516b.../clips/clip-4.ass:20  Dialogue: 0,0:00:10.40,0:00:10.100,...,last but not
```

Six occurrences across two of five clips in one video. It never showed up in the raw word timings (0 of
16,584 round to .100) because the value that gets formatted is `word.start - clipStart`, so the clip
offset is what pushes it over.

**Fix:** carry the overflow into the seconds field (`.995` → next second, `.00`), or clamp to 99. Found
while porting `SubtitleWriter` to `scripts/render-variant.py`; that port clamps, so it reproduces clip 1
byte-for-byte but deliberately diverges from the Java output at exactly these six timestamps.

---

## E. Documentation drift

These are listed because they actively misdirect, not for tidiness.

### E1 — README contradicts itself on where the data goes

Line 9: "Everything runs on your machine. No uploads to third parties, no per-call API costs."
Line 33: "Access to a Sekisho-compatible OpenAI gateway."

Transcripts are being sent to a remote gateway. For a client-facing repo this is the sentence that
matters most, and it is wrong.

### E2 — CLAUDE.md claims observability that was removed

It states stages and tools are wrapped in Micrometer `Observation`s and that a video is one trace.
There is no `Observation` or `micrometer` reference in `src/main/java`; commit `7883861`
("refactor(observability): remove observations from orchestrator") took them out. The OTel starter
still exports JVM and HTTP metrics, so the dashboards look alive while the per-stage timings — the
numbers this project is actually tuned on — are gone.

### E3 — CLAUDE.md still describes Ollama

`num-thread`, `num-predict`, ADR-0001 "inference is fully local". The pom ships
`spring-ai-starter-model-openai` and there is no Ollama config anywhere. The `num-predict` guidance
survives in spirit as `spring.ai.openai.chat.max-tokens: 512`, which is worth saying explicitly since
you paid hours for that lesson.

### E4 — CLAUDE.md's inventory is stale

"49 files, ~2k lines", six stages, "M1 is complete". Actual: 80 Java files, ~3.3k lines, nine stages,
plus review, dashboard and content generation.

### E5 — The Dockerfile cannot run the pipeline

It installs ffmpeg and yt-dlp but not `whisper-ctranslate2` — the musl/ctranslate2 problem CLAUDE.md
documents. A container built from it reaches TranscriptionStage and dies with
`ToolNotAvailableException`. Either delete it, or move to a glibc base (`eclipse-temurin:25-jre-noble`)
and `pipx install whisper-ctranslate2`. Also: `./storage` inside the container is not a volume, so
clips die with the container.

### E6 — CI is a compile gate

`./mvnw clean verify` with no tests and no Postgres. Correct for "no tests for now" — worth naming it
as such in the README so it isn't mistaken for coverage.

---

## Suggested order

1. **A1, A2** — both produce a dashboard that lies about what happened.
2. **A9, A6** — a hang with no timeout, and a 500 on a double-click.
3. **A3, A5, D5, D6** — output quality and wasted work.
4. **B1, B2, B3, C1, C2, C4, D1-D4, D7** — mechanical, one sitting.
6. **A4, A7, A8** — design calls; your sequencing.
7. **E1-E6** — docs, once the code above settles.

## Verification

No tests in this project, so: `./mvnw clean compile` (clean, per CLAUDE.md — incremental reports
success against stale classes), then a full run of one short video, checking `storage/{id}/` for the
nine artifacts and the DB row's `status` / `last_error` directly rather than polling
`GET /videos/{id}`. For A1 specifically, re-run video `ed94f058` (34 candidates, currently 0 scored)
and confirm it now fails loudly with the score distribution instead of arriving empty at
READY_FOR_REVIEW. You run the app; I'll hand over the commands.
