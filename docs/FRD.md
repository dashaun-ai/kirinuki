# Kirinuki — Functional Requirements Document (FRD)

**Status:** Draft · design phase
**Owner:** your_javaguy
**Related:** [PRD](PRD.md) · [Architecture](ARCHITECTURE.md)

This document defines *what the system must do*. It is model-agnostic — where an inference model or
library is needed, it is referred to by role (e.g. "ASR service"), not by name. It maps onto the
pipeline stages described in [Architecture](ARCHITECTURE.md).

**Conventions**
- **MUST** = required · **SHOULD** = strongly preferred · **MAY** = optional.
- Requirement IDs: `FR-<AREA>-<n>`. Reference them in issues, commits, and tests.
- "Local storage" = the shared filesystem blackboard; "workflow" = the per-video pipeline driven by the
  Spring-native `PipelineOrchestrator`.

---

## Actors

| Actor | Role |
|---|---|
| **Creator** | Submits YouTube URLs, reviews, edits, approves, downloads, triggers publish |
| **Pipeline orchestrator** (Spring `@Service`) | Drives stages, retries, fan-out/in, human pause |
| **Inference services** (local models) | ASR, OCR, audio analysis, visual analysis, LLM reasoning |
| **Media workers** | FFmpeg-based deterministic transforms |
| **Publishing workers** | Per-platform upload + metadata (optional) |

---

## FR-ING — Ingestion

| ID | Requirement |
|---|---|
| FR-ING-1 | The system MUST accept a YouTube video URL via the API, download the source with **yt-dlp**, and store the raw file in local storage before any processing. |
| FR-ING-2 | The system MUST persist video metadata (id, source URL, YouTube id, title, duration, ingest time, owner) to the database on ingest, reading title/duration/uploader from yt-dlp. |
| FR-ING-3 | The system MUST create and start exactly one workflow per ingested video. |
| FR-ING-4 | Download failures (unavailable, private, region-locked, yt-dlp error) MUST surface a clear error and MUST NOT start a workflow. |
| FR-ING-5 | The system MUST reject unsupported/corrupt inputs with a clear error and MUST NOT start a workflow for them. |
| FR-ING-6 | Ingest MUST be idempotent — re-submitting the same YouTube id MUST NOT create a duplicate video or workflow (unique constraint on YouTube id). |
| FR-ING-7 *(future)* | The system MAY monitor a connected YouTube account/channel and auto-ingest new uploads on a schedule: the **YouTube Data API** detects new `videoId`s (the channel's uploads playlist), and the **same yt-dlp path** downloads them. |
| FR-ING-8 *(future)* | Auto-monitoring MUST require the creator's OAuth authorization, MUST dedup against already-ingested `videoId`s (FR-ING-6), and MUST NOT ingest content the account has no rights to. A poll failure MUST NOT affect running pipelines. |

**Inputs:** a YouTube URL (manual) or a new `videoId` from a monitored channel (auto). **Outputs:** downloaded `source.mp4`, `video` record, running workflow. **Status after:** `UPLOADED`.

---

## FR-WF — Workflow & state

| ID | Requirement |
|---|---|
| FR-WF-1 | The database `video.status` column MUST be the authority for pipeline state; the `PipelineOrchestrator` drives transitions. |
| FR-WF-2 | The workflow MUST progress through the states defined in [Architecture](ARCHITECTURE.md#workflow-state). |
| FR-WF-3 | Every expensive stage MUST run with a timeout and automatic retry of transient failures (Spring Retry `@Retryable`). |
| FR-WF-4 | The workflow MUST pause at `READY_FOR_REVIEW` indefinitely (a status row — nothing running) until the human decision is received; the approve action resumes it. |
| FR-WF-5 | Independent analysis stages MUST run in parallel on virtual threads (fan-out) and the orchestrator MUST join until all required results exist (fan-in) without polling. |
| FR-WF-6 | A crash or restart MUST resume via a startup recovery scan: re-drive any non-terminal, non-paused video; completed stages are skipped because their artifact already exists — no completed work is repeated (see FR-STO-3). |

---

## FR-MEDIA — Media preparation

| ID | Requirement |
|---|---|
| FR-MEDIA-1 | The system MUST extract an audio track from the source video and store it. |
| FR-MEDIA-2 | The system MUST produce a normalized video suitable for downstream analysis and rendering. |
| FR-MEDIA-3 | The system MUST extract frames using **sparse sampling or on-demand extraction** — it MUST NOT store every frame of the source. |
| FR-MEDIA-4 | All media-prep outputs MUST be written to local storage keyed by `(videoId, stage)`. |

**Status after:** `MEDIA_PREPARATION` → `FEATURE_EXTRACTION`.

---

## FR-FEAT — Feature extraction (parallel)

Each extractor is independent, publishes its own result, and never communicates with the others.

| ID | Requirement |
|---|---|
| FR-FEAT-1 | **Transcript** — the ASR service MUST produce a transcript with **word-level timestamps** (required later for boundary snapping and subtitles). |
| FR-FEAT-2 | **Scenes** — the system MUST produce scene/shot boundaries with timestamps. |
| FR-FEAT-3 | **OCR** — the system MUST extract on-screen text (slides, code, captions) with timestamps. |
| FR-FEAT-4 | **Audio features** — the system MUST detect silence, laughter, applause, and excitement markers with timestamps. |
| FR-FEAT-5 | **Visual features** — the system MUST detect faces, gestures, screen-share, and transitions with timestamps. |
| FR-FEAT-6 | Each extractor MUST write a self-contained result artifact to local storage; a failure in one MUST NOT fail the others. |
| FR-FEAT-7 | The workflow MUST proceed only when all required feature artifacts exist. |

**Status after:** `WAITING_FOR_FEATURES` → `CANDIDATE_GENERATION`.

---

## FR-CAND — Candidate generation (deterministic)

| ID | Requirement |
|---|---|
| FR-CAND-1 | Candidate segments MUST be generated **deterministically** — no model call in this stage. |
| FR-CAND-2 | Candidates MUST be derived from **scene + transcript sentence boundaries** so each is semantically coherent (not arbitrary fixed windows). |
| FR-CAND-3 | The number of candidates MUST be bounded (tens, not hundreds) to fit the downstream reasoning stage. |
| FR-CAND-4 | Each candidate MUST carry its start/end plus references to the transcript, scene, OCR, audio, and visual data spanning it. |
| FR-CAND-5 | The system MAY apply a cheap heuristic/embedding pre-filter before scoring. |

**Output:** `candidates.json`.

---

## FR-SCORE — AI scoring & ranking

| ID | Requirement |
|---|---|
| FR-SCORE-1 | The LLM reasoning service MUST evaluate each candidate and return a **structured** result: hook, educational value, emotion, virality, suggested platforms, and a short reason. The model MUST NOT return an overall score — that is computed from configured weights so ranking stays explainable and tunable. |
| FR-SCORE-2 | Structured output MUST be schema-validated; an invalid response MUST cause the activity to fail and retry, never propagate malformed data. |
| FR-SCORE-3 | Candidates MUST be scored **one per call**, so a malformed or slow response affects one candidate rather than a batch. |
| FR-SCORE-4 | The system MUST rank candidates by overall score and keep a configurable **top N** (default 8). |
| FR-SCORE-5 | The system MUST drop candidates whose overall score is below a configurable **minimum floor** (`scoring.min-score`, default 0 = disabled; 50 in the shipped profiles) before the top-N cut, so a weak source yields a few strong clips instead of a padded list. |

**Status after:** `AI_ANALYSIS` → `CLIP_RENDERING`.

---

## FR-BND — Boundary refinement (deterministic)

| ID | Requirement |
|---|---|
| FR-BND-1 | Final clip start/end MUST be snapped **deterministically** to the nearest sentence end, silence gap, or scene cut using existing word-level timestamps — no model call. |
| FR-BND-2 | Refinement MUST avoid mid-sentence cuts and leading/trailing dead air. |
| FR-BND-3 | Refined boundaries MUST stay within a configurable tolerance of the scored candidate's range. |
| FR-BND-4 *(future)* | Refinement MAY add an **LLM pass over the selected top-N clips only** to choose a hook-aligned start and a complete-thought end that deterministic sentence/silence snapping (FR-BND-1) cannot judge — a sentence can end while the *thought* continues. The model's timestamps are advisory: final cut points MUST snap to real word timestamps, MUST respect the FR-BND-3 tolerance and clip-duration bounds, and MUST fall back to deterministic snapping if the call fails. Runs on ~top-N clips, not all candidates, so cost stays small. |

---

## FR-RENDER — Rendering (deterministic)

| ID | Requirement |
|---|---|
| FR-RENDER-1 | For each selected clip the system MUST cut the segment, reframe to vertical, resize, and burn in subtitles. A watermark MAY be applied. |
| FR-RENDER-2 | Subtitles MUST be derived from the word-level transcript and aligned to the clip. |
| FR-RENDER-3 | Rendered clips MUST be written to local storage under the video's key. |
| FR-RENDER-4 | Rendering MUST be idempotent per `(videoId, clip)` — re-running reproduces the same output without duplication. |
| FR-RENDER-5 | Thumbnails/cover images are **out of scope**: they are produced by a separate tool, not this pipeline. |
| FR-RENDER-6 *(future)* | Reframing SHOULD become **content-aware**: detect the region of interest (active code / speaker) and crop-and-zoom it to fill the 9:16 frame, so screen-share content is legible on mobile instead of sitting in a ~35% band inside a blurred fill. The current blur-fill (FR-RENDER-1) is the deterministic fallback; this depends on the M3 visual/OCR extractors. |

**Status after:** `CLIP_RENDERING` → `CONTENT_GENERATION`.

---

## FR-CONTENT — Content generation

| ID | Requirement |
|---|---|
| FR-CONTENT-1 | For each clip the system MUST generate universal content: summary, keywords, tags. |
| FR-CONTENT-2 | For each clip the system MUST generate per-platform variants (e.g. TikTok caption + hashtags, LinkedIn post + CTA, X tweet + thread suggestion, YouTube title + description + SEO tags). |
| FR-CONTENT-3 | All generated content MUST be structured, schema-validated, and persisted; nothing is posted at this stage. |
| FR-CONTENT-4 | Content generation for one clip MUST NOT block or fail others. |

**Status after:** `CONTENT_GENERATION` → `READY_FOR_REVIEW`.

---

## FR-REVIEW — Review dashboard

| ID | Requirement |
|---|---|
| FR-REVIEW-1 | The dashboard MUST list all clips for a video with: preview, transcript, title, caption, hashtags, suggested platforms, and confidence score. |
| FR-REVIEW-2 | The creator MUST be able to **edit** any generated text field. |
| FR-REVIEW-3 | The creator MUST be able to **approve**, **reject/delete**, and **download** any clip. |
| FR-REVIEW-4 | The creator MUST be able to **regenerate** a clip's title or caption on demand (re-invokes the reasoning service for that field only). |
| FR-REVIEW-5 | The workflow MUST remain paused until the creator acts; the dashboard MUST reflect current status. |
| FR-REVIEW-6 | Edits and approvals MUST persist and MUST be the source of truth for any later publish. |

**Status after (on approval):** `READY_TO_PUBLISH`.

---

## FR-PUB — Publishing (optional, phased)

| ID | Requirement |
|---|---|
| FR-PUB-1 | Publishing MUST be **off by default**; the workflow completes at asset-ready without posting. |
| FR-PUB-2 | Each platform MUST have an independent enable/auto-publish toggle. |
| FR-PUB-3 | The creator MUST be able to publish immediately, schedule for later, or download and post manually. |
| FR-PUB-4 | Each publisher MUST refresh its OAuth tokens, upload the clip, apply the generated metadata, and store the returned post id/URL. |
| FR-PUB-5 | Publishing MUST be per-platform independent — a failure on one platform MUST NOT affect the others, and each MUST retry transient failures independently. |
| FR-PUB-6 | Platforms MUST be added incrementally; the system MUST function fully with zero platforms connected (download-only). |

**Status after:** `PUBLISHED` (optional terminal state).

---

## FR-SERIES — Series packaging *(future)*

A **series** is an ordered, themed group of clips (from one or more source videos) packaged for
sequential release ("Part 1/2/3").

| ID | Requirement |
|---|---|
| FR-SERIES-1 | The system MUST allow clips to be grouped into a named series with an explicit order. |
| FR-SERIES-2 | Series candidates SHOULD be proposed by deterministic clustering (topic/keywords/scene), then named/themed/ordered by the reasoning service. |
| FR-SERIES-3 | Each series item MUST carry per-episode metadata (hook, position, "Part n of m"). |
| FR-SERIES-4 | Series data MUST be persisted (`series`, `series_item`) and MUST reference existing clips without duplicating them. |

---

## FR-ANALYTICS — Analytics learning loop *(future)*

Closes the loop: **publish → measure → learn → influence next scoring & sequencing.**

| ID | Requirement |
|---|---|
| FR-ANALYTICS-1 | The system MUST ingest per-post performance stats (views, watch time, retention, likes, comments, shares) from platform APIs and persist them (`clip_performance`). |
| FR-ANALYTICS-2 | A recommendation service MUST reason over aggregated performance to suggest what to make/post next and how to order a series, with a short justification. |
| FR-ANALYTICS-3 | Candidate scoring MAY accept historical **performance priors** as additional context; the pipeline MUST function fully with no priors (cold start). |
| FR-ANALYTICS-4 | Performance ingestion MUST be independent and retryable; a stats fetch failure MUST NOT affect the core pipeline. |

---

## FR-STO — Storage & idempotency

| ID | Requirement |
|---|---|
| FR-STO-1 | Local storage (the filesystem rooted at `kirinuki.storage.root`) MUST be the source of truth for all intermediate and final artifacts; the database stores metadata and pointers, not blobs. |
| FR-STO-2 | Every artifact MUST be keyed by `(videoId, stage)` with a defined path layout under `kirinuki.storage.root`. |
| FR-STO-3 | Before recomputing, an activity MUST check for an existing artifact and reuse it — expensive stages are never recomputed unless explicitly requested. |
| FR-STO-4 | Retries MUST use overwrite-on-retry semantics (a retry replaces, never duplicates). |
| FR-STO-5 | The system MUST support a retention/cleanup policy for large intermediates (raw, normalized, frames). |

---

## FR-AUTH — Accounts & security

| ID | Requirement |
|---|---|
| FR-AUTH-1 | API access MUST require authentication (JWT); a video and its clips MUST be scoped to their owner. |
| FR-AUTH-2 | Platform credentials/tokens MUST be stored securely and never exposed to the client. |

---

## FR-NFR — Non-functional

| ID | Requirement |
|---|---|
| FR-NFR-1 | The pipeline MUST be resumable: after any crash/restart, no completed stage is repeated (verifies FR-WF-6 / FR-STO-3). |
| FR-NFR-2 | Failures MUST surface via structured error responses (ProblemDetail); no stage silently swallows errors. |
| FR-NFR-3 | The system MUST emit traces, metrics, and logs for each stage (OpenTelemetry). |
| FR-NFR-4 | Adding a new platform, model, or analysis stage MUST be achievable by adding one workflow activity, without redesigning the pipeline. |
| FR-NFR-5 | All external service tags/versions MUST be pinned; no `latest`. |

---

## Acceptance criteria (end-to-end)

A build satisfies this FRD when, for a real long-form input:

1. Submitting a YouTube URL downloads the source, produces a running workflow, and reaches `READY_FOR_REVIEW` unattended. *(FR-ING, FR-WF)*
2. The review dashboard shows ranked clips, each with preview, subtitles, title, caption, hashtags,
   platforms, and confidence. *(FR-REVIEW, FR-RENDER, FR-CONTENT)*
3. Boundaries land on clean sentence/silence points; no mid-word or dead-air cuts. *(FR-BND)*
4. Editing and approving a clip persists; regenerate updates only the targeted field. *(FR-REVIEW)*
5. Killing the app mid-run and restarting resumes without recomputing completed stages. *(FR-NFR-1)*
6. With zero platforms connected, the creator can still download every finished clip. *(FR-PUB-6)*
7. Enabling a platform publishes independently and records the returned post URL; a failure there
   leaves other platforms unaffected. *(FR-PUB-5)*
