# Architecture Decision Records (ADRs)

Chronological log of significant, hard-to-reverse decisions. One record per decision.
Statuses: **Proposed** → **Accepted** → (later) **Superseded by ADR-XXXX** / **Deprecated**.

| ADR | Title | Status |
|---|---|---|
| [0001](#adr-0001--model--hosting-choice) | Model & hosting choice | 🟢 Accepted — fully local |
| [0002](#adr-0002--spring-native-orchestration-drop-temporal) | Spring-native orchestration (drop Temporal) | 🟢 Accepted |
| [0003](#adr-0003--youtube-ingest-via-yt-dlp) | YouTube ingest via yt-dlp | 🟢 Accepted |
| [0004](#adr-0004--channel-auto-monitoring-poll-vs-push) | Channel auto-monitoring: poll vs. push | 🟢 Accepted (poll primary) |

---

## ADR-0001 — Model & hosting choice

- **Status:** Accepted — **fully local / self-hosted**
- **Date:** 2026-07-22
- **Deciders:** your_javaguy
- **Related:** [PRD](PRD.md) · [FRD](FRD.md) · [Architecture](ARCHITECTURE.md#open-decisions)

### Context

Kirinuki's pipeline defines its inference stages by *role*, not by model (see FRD preamble): an
**LLM reasoning** service (candidate scoring + content generation), a **speech-to-text (ASR)**
service, and — depending on how far we push automated analysis — **OCR**, **audio analysis**, and
**visual analysis**. This ADR decides **where those models run**.

### Decision

**Every inference role runs on self-hosted, local models.** Nothing (source video, audio, transcript)
leaves the box, and there is no per-call cost. The LLM is served locally (e.g. **Ollama**) behind
Spring AI's `ChatClient`, reached over its **OpenAI-compatible HTTP API** — see the amendment below. The **exact model per role is still tuned** at the M1 quality gate — only the
hosting axis is fixed here.

| Role | Model | Where it runs | Notes |
|---|---|---|---|
| LLM reasoning | _TBD (local)_ | Local, over an OpenAI-compatible endpoint (Ollama serves one at `:11434/v1`) | Must support schema-constrained / structured output (FR-SCORE-2) |
| Speech-to-text (ASR) | _TBD (local)_ | Local (Whisper — `faster-whisper` / `whisper.cpp`) | Must emit word-level timestamps (FR-FEAT-1) |
| OCR | _TBD (local)_ | Local (Tesseract or local VLM) | |
| Audio analysis | _TBD (local)_ | Local | |
| Visual analysis | _TBD (local)_ | Local | |

### Amendment — 2026-08-26: transport is OpenAI-compatible, hosting is unchanged

The app moved off the Ollama-specific Spring AI starter to `spring-ai-starter-model-openai`, configured
by `OPENAI_BASE_URL` / `OPENAI_API_KEY` with no defaults. **This does not change the decision.** Ollama
serves an OpenAI-compatible API at `:11434/v1`, so a local deployment still satisfies every criterion
below. What changed is that the client is no longer provider-specific — the hosting axis is now a
deployment-time choice rather than a compile-time one.

That cuts both ways, and it is the reason this amendment exists rather than a silent config change:
**pointing `OPENAI_BASE_URL` at a hosted gateway violates this ADR**, because transcripts then leave the
box. Nothing in the code prevents it. If a hosted endpoint is ever wanted deliberately, supersede this
ADR rather than quietly changing an environment variable.

Two Ollama-native knobs were lost in the move — `num-thread` and `num-ctx` are not part of the OpenAI
chat API and can no longer be set per request. See [TOOLS](TOOLS.md#4-the-chat-model--spring-ai).

### Decision criteria

- **Privacy** — source video/transcripts must not leave the environment. *(Local satisfies this outright.)*
- **Cost** — no per-call/per-video API cost.
- **Output quality** — especially moment-discovery (the metric that matters, per PRD §5).
- **Structured-output reliability** — the reasoning model must reliably return schema-valid results.
- **Hardware fit** — what the target machine can actually run concurrently.

### Consequences

- **Enables:** full data control (private by construction), zero marginal inference cost, offline
  operation.
- **Constrains:** throughput is **hardware/VRAM-bound**; the operational concern becomes VRAM budgeting
  and keeping models warm. Local models are less reliable at strict JSON than hosted APIs, so
  structured output must be **engineered** — Spring AI structured output + schema validation +
  `@Retryable`, and a model/serving mode that supports JSON/grammar-constrained decoding.
- **Follow-on work:** local serving setup (Ollama + model pulls), per-role model selection at M1, a
  concurrency/scheduling budget for the target machine.

### Open questions (deferred to per-role selection, not hosting)

- Which specific local model best fits each role at the M1 quality bar?
- What hardware is the target deployment, and what can it run at once?
- Is grammar-constrained decoding needed at the serving layer, or is prompt-level structuring enough?

---

## ADR-0002 — Spring-native orchestration (drop Temporal)

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** your_javaguy
- **Related:** [Architecture](ARCHITECTURE.md#workflow-state) · [FRD](FRD.md) (FR-WF)

### Context

An earlier design named **Temporal** as the durable workflow engine. Kirinuki is, in practice, **one
mostly-linear pipeline per video** with a single parallel fan-out (the five extractors) and one
indefinite human-review pause. Temporal's headline features — durable resume and durable human-pause —
are already covered by the design's own contract: every stage is idempotent and keyed by
`(videoId, stage)`, and the review pause is just a status row (nothing is running during it). Temporal
would add a server (and its own datastore) plus a whole programming model to run and teach — against the
project's goals of *fully local, Spring-native, and teachable*.

### Decision

Drop Temporal. Orchestrate with a Spring `@Service` **`PipelineOrchestrator`**:

- **Postgres `video.status`** is the authority; the orchestrator drives transitions.
- **Resume = skip-if-artifact-exists:** each stage checks for its output in local storage and skips if
  present (FR-STO-3). A startup **recovery scan** re-drives any non-terminal, non-paused video.
- **Retries** via Spring Retry (`@Retryable`, SF7 `@EnableResilientMethods`).
- **Fan-out/fan-in** on **virtual threads** (`spring.threads.virtual.enabled=true`), joined before
  candidate generation.
- **Human pause** at `READY_FOR_REVIEW` returns from the orchestrator; the approve action calls
  `resume(...)`.

### Consequences

- **Enables:** no extra server, fully local, one framework to learn; durability falls out of the storage
  contract already required.
- **Trade-off:** a crash **mid-stage** re-runs that whole stage (partial in-stage progress is lost).
  Cheap and correct because stages are idempotent and artifact-keyed — only the interrupted stage repeats.
- **When to revisit:** if Kirinuki ever runs many concurrent long workflows, complex saga/compensation
  logic, or distributed workers needing exactly-once coordination, reconsider a durable engine.

---

## ADR-0003 — YouTube ingest via yt-dlp

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** your_javaguy
- **Related:** [FRD](FRD.md) (FR-ING) · [Architecture](ARCHITECTURE.md#pipeline-flow)

### Context

The source is a YouTube video. The system needs both the **video file** and its **metadata**. The
official **YouTube Data API** returns metadata only and does **not** permit downloading video content,
so it cannot acquire the source file.

### Decision

Acquire the source with **yt-dlp**: the creator submits a URL, the API shells out to yt-dlp to download
`source.mp4` into local storage and to read metadata (`yt-dlp -J`: title, duration, uploader, id).
yt-dlp is treated as an **external, pinned binary** — exactly like FFmpeg — invoked as a subprocess and
baked into the Docker image.

### Consequences

- **Enables:** single-tool acquisition of both content and metadata; no API quota/keys for ingest.
- **Constrains:** yt-dlp can break when the site changes → **pin the version** and update deliberately.
- **Policy:** only ingest videos the creator **owns or has rights to** repurpose (rights-holder
  content — typically the creator's own uploads). Enforced by product convention, noted in PRD §8.
- **Later:** channel/playlist auto-monitoring (scheduled poll + dedup by YouTube id) layers on top of
  the same download path — see [ADR-0004](#adr-0004--channel-auto-monitoring-poll-vs-push).

---

## ADR-0004 — Channel auto-monitoring: poll vs. push

- **Status:** Accepted — **poll primary; push deferred**
- **Date:** 2026-07-22
- **Deciders:** your_javaguy
- **Related:** [FRD](FRD.md) (FR-ING-7/8) · [ADR-0003](#adr-0003--youtube-ingest-via-yt-dlp) · [PRD](PRD.md) (M5)

### Context

Manual URL submit (ADR-0003) is the M1 path. Since sources live in the creator's own YouTube account,
we want new uploads ingested **hands-free**. Detection and acquisition split cleanly: the **Data API**
(or a feed) detects a new `videoId`; **yt-dlp** still downloads it. Three detection mechanisms exist:

1. **Poll the uploads playlist (Data API)** — `playlistItems.list`, ~1 quota unit/call (10k/day budget),
   so frequent polling is effectively free. With the creator's **OAuth**, it also sees **unlisted/
   private** own uploads.
2. **WebSub / PubSubHubbub push** — YouTube POSTs a callback on new uploads: near-real-time, no quota.
   But needs a **public callback endpoint**, a **~5-day subscription lease** to renew, fires on edits
   (dedup), has no delivery guarantee (needs a reconciliation poll), and is **public-only**.
3. **RSS/Atom feed poll** — no key/quota, but public-only and ~15 latest. Prototype-grade.

### Decision

**Primary: scheduled poll of the connected channel's uploads playlist via the OAuth'd YouTube Data
API.** New `videoId`s are deduped (FR-ING-6) and handed to the existing yt-dlp download → pipeline path.
A Spring `@Scheduled` poller uses `RestClient` (or a `@HttpExchange` client) on virtual threads; the
connected channel + OAuth tokens are stored securely (FR-AUTH-2).

WebSub push is **deferred** — added later only if near-real-time latency matters, with the poll kept as
the reconciliation backstop. It is deliberately not primary because it reintroduces a public webhook
(the StreamYard shape we removed), a lease to babysit, and can't see unlisted/private uploads.

### Consequences

- **Enables:** hands-free ingest covering the creator's private/unlisted uploads; no public endpoint,
  no lease management; trivial quota cost.
- **Constrains:** detection latency is the poll interval (minutes, not seconds) — acceptable for a batch
  pipeline; requires OAuth scope for the user's channel.
- **Trade-off vs push:** we accept small latency to avoid callback infrastructure and lease renewal.

