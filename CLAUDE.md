# Kirinuki — project instructions

Long-form YouTube video → short-form clip pipeline. Storage, Postgres and ASR/ffmpeg are local; the
chat model is reached over an **OpenAI-compatible HTTP endpoint** (see *Inference* below). Read `docs/`
in order: PRD → FRD → ARCHITECTURE → DECISIONS.

## How we work

- I act as **senior engineer + product manager**: I own feature sequencing and reviews.
- **You research and implement** each feature; I review against the house conventions, then we proceed.
- **Tests are expected.** Unit tests beside the class, `*IntegrationTest` for anything crossing HTTP or
  the database. Integration tests run under failsafe (`verify`), not surefire (`test`).
- **No comments in the code.** Not even WHY comments, and no FRD/spec ids in comments.
- State the approach and let the user confirm before implementing non-trivial work. Name every new
  dependency and ask before adding it or any new abstraction.
- **Never start or stop the app** — I run it. Make the change and hand over the commands. Don't poll
  `GET /videos/{id}` in a loop either; it logs on every call and floods my console. Check the
  filesystem and Postgres directly instead.
- Verify with `clean compile`, not `compile` — an incremental build reports success against stale
  classes.
- **Never `git commit` or `git push`** unless I ask in that message. Draft the message; I commit.
  Never add a `Co-Authored-By: Claude` trailer.

## Current state

**M1–M4 are complete.** A YouTube URL goes in, finished vertical clips come out with per-platform
content, and the review dashboard edits/approves/regenerates them.

- Spring Boot 4.1.0 / Java 25, package `ai.dashaun.kirinuki`. 81 main files, ~3.4k lines, 19 test files
  (179 unit + 42 integration).
- Spine: `KirinukiApplication`, `config/` (`KirinukiStorageProperties`, `KirinukiYtDlpProperties`,
  `KirinukiPipelineProperties`), `storage/StorageService`, `common/` — base `KirinukiException` carries
  its `HttpStatus` + title, so each advice stays one handler method.
- Two exception advices: `common/GlobalExceptionHandler` is
  `@RestControllerAdvice(annotations = RestController.class)` and returns `ProblemDetail`;
  `dashboard/DashboardExceptionHandler` is scoped to `DashboardController` and renders
  `dashboard-error.html`. A browser must never get a raw ProblemDetail body.
- `common/ProcessRunner` runs every external binary (yt-dlp, ffmpeg, whisper) with virtual-thread
  stream drains and a timeout. A missing binary throws `ToolNotAvailableException`, which is never
  retried.
- `pipeline/` — `PipelineOrchestrator` holds `List<PipelineStage>` and nothing else; each stage
  declares its artifact, status and `run(video)`. Skip-if-artifact-exists and status transitions live
  in one loop. Sibling stages sharing a status fan out on virtual threads; the first failure is thrown
  with the rest suppressed.
- `PipelineRecovery` re-drives on startup by filtering `PipelineStatus.isResumable()`. Idempotency
  comes from skip-if-artifact-exists inside the orchestrator, not from the recovery scan.
- Stages in order: `DownloadStage` → `MediaPreparationStage` →
  (`TranscriptionStage` ∥ `SceneExtractionStage` ∥ `AudioExtractionStage`, all three on
  `FEATURE_EXTRACTION`) → `CandidateGenerationStage` → `ScoreCandidatesStage` → `RenderClipsStage` →
  `ContentGenerationStage`.
- Artifacts are written to a `.part` file and atomically committed by the orchestrator, so a killed
  subprocess never leaves a truncated file that `exists()` would trust. The marker goes **before** the
  extension (`audio.part.wav`) because ffmpeg and yt-dlp both derive the container from it.
- `POST /videos` returns `202` at `DOWNLOADING` in ~3s; the download is a pipeline stage.
  `GET /videos/{id}` carries `lastError`. `POST /videos/{id}/advance` re-drives (409 if not resumable).
  `GET /videos/{id}/{transcript,candidates,scored,clips,content}` and `/clips/{index}` for the MP4.
- Review API under `/videos/{id}/review`: `GET`, `PUT /{clipIndex}`, `POST /{clipIndex}/{approve,
  reject,regenerate}`, `POST /approve`. Thymeleaf dashboard at `/` and `/videos/{id}/dashboard`.
- Scoring: one candidate per LLM call via `ChatClient` (the model stays config, never in Java). The
  model returns sub-scores only; `overallScore` is a weighted sum computed in `CandidateScorer`, so
  ranking is explainable and tunable via `scoring.weights`. Four dimensions — hook, educationalValue,
  emotion, virality. **Visual interest was removed**: it cannot be judged from a transcript, and the
  structured-output schema kept asking for it however the prompt was worded.
- Content generation is per clip and must never drop one — a failed call writes a blank `ClipContent`
  carrying the clip's suggested platforms, because `ReviewService` seeds review rows from
  `content.json` and a missing entry makes a rendered clip invisible forever.
- Migrations: `V1__video.sql`, `V2__video_last_error.sql`, `V3__clip_review.sql`. Later feature tables
  get their own `V<n>__`.
- Observability: stages and external tools are wrapped in Micrometer `Observation`s, so a video is one
  trace (`kirinuki.pipeline` → `kirinuki.stage` → `kirinuki.tool` / `spring_ai chat_client`) with
  timer metrics per stage and tool. Spring AI also emits `gen_ai_*` token and latency metrics.

## Inference

The app talks to an **OpenAI-compatible endpoint** via `spring-ai-starter-model-openai`. There are no
credentials in the repo — `OPENAI_BASE_URL` and `OPENAI_API_KEY` have no defaults, so a missing one
fails at startup rather than silently reaching a hardcoded host. `CHAT_MODEL` defaults to
`llama4:latest`. Copy `.envrc.example` to `.envrc` and fill it in.

> **ADR-0001 still holds.** Ollama serves an OpenAI-compatible API at `:11434/v1`, so a local
> deployment satisfies it exactly as before — the client stopped being provider-specific, the hosting
> decision did not change. The corollary matters: pointing `OPENAI_BASE_URL` at a hosted gateway
> *would* violate ADR-0001, because transcripts then leave the box, and nothing in the code stops you.
> See the 2026-08-26 amendment in `docs/DECISIONS.md`.

## Hardware

**This laptop is CPU-only, 12 cores, 38GB, often on battery. The client's box has a 24GB 4090 + 32GB
5090** — size GPU config for the 4090 so it fits both. Local runs prove the pipeline works; judge clip
quality only on the GPU box.

If the endpoint is ever pointed back at a local Ollama, two numbers matter and cost hours to rediscover:
`num-predict` **must** be bounded, or a rambling reply runs to the context limit and costs minutes per
call; and `num-thread` belongs at 6 of 12 so the IDE and Docker stay usable. Scoring a 14-minute video
(28 candidates) took ~30 min on 6 CPU threads. Rendering is ~1.8x realtime.

## Infra

`docker-compose.yml` = Postgres 17-alpine + `grafana/otel-lgtm` (tag **not** pinned). No `Dockerfile`
in git — it is untracked, because ctranslate2 ships no musl wheels so an Alpine runtime cannot host the
ASR binary. **No security at all yet** (M5): every endpoint and the whole dashboard are open.

`docs/*.md` and `CLAUDE.md` are deliberately untracked. They are **not** gitignored, so never
`git add -A` — use `git add -u` plus explicit paths.

## Next feature

M4 shipped, so the fork is now M3 vs M5.

- **M3 — finish the analysis**: OCR and visual extractors are still missing (scenes and audio are
  done). This is also the prerequisite for ROI cropping (`FR-RENDER-6`).
- **M5 — accounts**: auth is the single largest gap between this and anything deployable.

One real M4 gap if you want it closed first: FR-CONTENT-2 asks for platform-*shaped* variants ("X
tweet + thread suggestion", "YouTube title + description + SEO tags"), but `PlatformVariant` is one
generic `(platform, title, caption, hashtags, callToAction)` applied to all four platforms.

Known limitation regardless: the source is a 720p screen-share, so a 9:16 crop gives 405×720 upscaled
to 1080×1920. Centre-cropping slices code in half (proven), so the renderer uses a blurred fill with
the video occupying ~40% of the frame. `render.zoom` trades dead space for cropped edges.

Publishing is **M6**, not M4 — `READY_TO_PUBLISH` is deliberately left `resumable=true` with no stage
declaring it, so the approved backlog drains automatically once a publish stage exists. Until then it
costs one no-op write and one log line per approved video per restart.

## Key decisions (see docs/DECISIONS.md)

- ADR-0001: inference is fully local (local ASR/OCR/audio/visual; LLM over a local OpenAI-compatible
  endpoint). Amended 2026-08-26 for the transport change — the hosting decision is unchanged.
- ADR-0002: no Temporal — Spring-native `PipelineOrchestrator` (Postgres-authoritative status,
  `@Retryable`, virtual threads, skip-if-artifact-exists resume).
- ADR-0003: YouTube ingest via yt-dlp (external pinned binary; Data API can't download).
- ADR-0004: channel auto-monitoring polls the uploads playlist (Data API detects, yt-dlp downloads);
  WebSub push deferred.
- Undrafted (ADR-0005): ASR as a long-lived local service rather than a per-invocation subprocess, so
  the model stays warm. Local-only either way; this is cold vs warm, not hosted vs local.

## Run

```bash
docker compose up -d                                # Postgres + Grafana LGTM
cp .envrc.example .envrc && direnv allow            # OPENAI_BASE_URL / OPENAI_API_KEY are required
./mvnw spring-boot:run                              # :8080, `local` profile by default
SPRING_PROFILES_ACTIVE=gpu ./mvnw spring-boot:run   # on the GPU box
./mvnw clean verify                                 # 179 unit + 42 integration (needs Docker)
```

Dashboard is `/`, health is `/actuator/health`, Grafana is on **`:3001`** (mapped from the container's
3000). `yt-dlp`, `ffmpeg` and `whisper-ctranslate2` must be on PATH
(`pipx install yt-dlp whisper-ctranslate2`).
