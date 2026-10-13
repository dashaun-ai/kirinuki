# Designed Edits — Implementation Plan

**Status:** Proposed · awaiting approval
**Milestone:** M9 — Designed edits (independent of M5/M6; needs M4, which is done)
**Related:** [PRD](PRD.md) · [FRD](FRD.md) · [Architecture](ARCHITECTURE.md) · [Experiments](EXPERIMENTS.md) · [Decisions](DECISIONS.md)

---

## 1. What this is

Today a clip is the source window, reframed onto a blurred fill, with static ASS captions. A **designed
edit** is the same clip — same boundaries, same voice — re-cut into a paced, animated short: kinetic
typography on the words that matter, Spring-themed visuals (logos, terminals, diagrams, stamps), punch-ins
on the real footage, word-highlight captions, sound design and a music bed that ducks under the speaker.

The creator's footage stays the spine. Animated shots cut in *around* it when the transcript calls for
them; the rest of the time the viewer sees Dashaun's screen, zoomed onto what he is talking about.

The technique is proven outside Kirinuki: the `voiceover-video` skill produced a 108s, 30-shot vertical
edit from a voice note with this exact engine. This plan ports it into the pipeline so it runs unattended
on local models.

### Why a designed edit, not more ffmpeg flags

`EXPERIMENTS.md` X9 already gets the most out of the ffmpeg path (pop captions, panel, progress bar,
title card). What ffmpeg cannot do is *change what is on screen based on what is being said*. That
needs a plan per clip and a renderer that can draw arbitrary scenes — a model and a browser.

### Non-goals

- **Not a timeline editor** (PRD NG1 still holds). The system proposes a plan; the creator edits shots
  field by field in the dashboard. There is no drag-and-drop timeline.
- **Not a replacement for the standard clip.** The original `clip-N.mp4` is always rendered; a designed
  edit is an additional variant the creator chooses to publish.
- **Not freeform generated HTML.** The model never writes markup or JavaScript (see §4).

---

## 2. How it fits the existing flow

```
                         ┌────────────── existing, unchanged ──────────────┐
POST /videos ─► DOWNLOADING ─► MEDIA_PREPARATION ─► FEATURE_EXTRACTION ─► CANDIDATE_GENERATION
            ─► AI_ANALYSIS ─► CLIP_RENDERING ─► CONTENT_GENERATION
                                                         │
                                          NEW ──► EDIT_PLANNING        (cheap: one LLM call per clip)
                                                         │
                                                  READY_FOR_REVIEW     (pipeline parks, as today)
                                                         │
                  dashboard: creator opens a clip, reviews/edits the plan, clicks "Render designed edit"
                                                         │
                                  NEW clip-level workflow (DesignedEditRunner, not the video status)
                     ASSETS ─► PREVIEW ─► VISION_QA ─► FRAMES ─► SOUND ─► ENCODE ─► READY
                                                         │
                                  creator approves the clip with variant = ORIGINAL | DESIGNED
                                                         │
                                                  READY_TO_PUBLISH (M6 publishes the chosen variant)
```

### Two deliberate splits

**1. Planning is a pipeline stage; rendering is not.** Planning is one model call per clip and every clip
gets a plan, so it belongs in the video status spine with skip-if-artifact-exists like every other stage.
Rendering is minutes of CPU/GPU per clip and only worth doing for clips the creator actually wants, so it
is triggered per clip from review. Putting rendering in the spine would make every video pay for eight
designed renders before anyone has looked at a single clip.

**2. The designed render is a clip-level workflow with its own table.** `video.status` is one enum for the
whole video and `READY_FOR_REVIEW` is not resumable. A per-clip render needs its own status, its own
`lastError`, its own resume, and optimistic locking against dashboard edits — so it gets a
`designed_edit` row per `(video_id, clip_index)`, mirroring how `clip_review` already sits beside
`video`.

### What changes in existing code

| Existing piece | Change | Why |
|---|---|---|
| `PipelineStatus` | insert `EDIT_PLANNING(true)` between `CONTENT_GENERATION` and `READY_FOR_REVIEW` | ordinal order is the flow; videos already parked at `READY_FOR_REVIEW` are untouched, videos mid-pipeline pick up the new stage |
| `Artifacts` | add `EDIT_PLANS = "edit-plans.json"`, `DESIGNED_DIRECTORY = "designed"` | the filename is the completion marker |
| `KirinukiPipelineProperties` | add `@DefaultValue Edit edit` record | all tuning in config, models included |
| `FfmpegClient` | add `renderDesigned(...)` (overlay + mix + encode) and `extractClipAudio(...)` | every binary still goes through `ProcessRunner` |
| `ReviewService` / `ClipReviewResponse` | expose plan summary + designed status per clip; approval carries `variant` | dashboard needs both in one read |
| `clip_review` | add `variant` column (`ORIGINAL` default) | M6 must know which file to publish |
| `dashboard.html` | new "Designed edit" panel per clip | §8 |
| `PipelineRecovery` | also re-drive `designed_edit` rows in a resumable status | same guarantee as the video spine |
| `Dockerfile` | runtime moves from Alpine to Debian (`eclipse-temurin:25-jre-noble`) | Playwright's Chromium is glibc-only — the same wall ctranslate2 already hit |

Nothing in download, extraction, candidates, scoring, standard rendering or content generation changes.

---

## 3. Storage layout (additions)

```
{kirinuki.storage.root}/{videoId}/
├── ... existing artifacts ...
├── edit-plans.json                     # EditPlanningStage — List<EditPlan>, one per clip
└── designed/
    └── clip-{n}/
        ├── plan.json                   # the plan this render used (frozen copy, incl. edits)
        ├── assets.json                 # resolved assets + credits
        ├── assets/                     # images/logos copied from the shared cache
        ├── preview/t{time}.png         # one still per shot
        ├── contact-sheet.png           # PREVIEW
        ├── qa.json                     # VISION_QA findings
        ├── frames/f{00000}.png         # FRAMES — transparent where the source shows through
        ├── cues.json                   # sound cues exported by the engine
        ├── sfx.wav  music.wav          # SOUND
        └── designed.mp4                # ENCODE — the deliverable

{kirinuki.storage.root}/_assets/        # shared, content-addressed download cache
└── {sha256}.{ext}  +  {sha256}.json    # source URL, licence, author, fetched-at
```

Every step writes through the existing `.part` commit scheme, so a killed render never leaves a frame
directory or mp4 that the skip check trusts. Frame directories commit as a directory rename.

---

## 4. The edit plan — the contract between model and renderer

The model returns **structured output**, the same way `CandidateScoreClient` returns `CandidateScore`.
It chooses from a fixed catalogue of blocks; Java validates; a static JS engine renders. The model never
emits HTML, CSS or JavaScript.

```java
public record EditPlan(int clipIndex, String theme, List<Shot> shots, List<Emphasis> emphasis) {}

public record Shot(
        double in,
        double out,
        ShotBlock block,
        String headline,
        String subline,
        List<String> lines,
        List<String> logos,
        String imageQuery,
        Double zoom,
        String transition) {}

public record Emphasis(String word, double at, EmphasisKind kind) {}
```

A **flat `Shot`** with a `block` enum and optional fields, not a sealed hierarchy: local models are
markedly less reliable at polymorphic JSON schemas than at one wide object, and the validator enforces
which fields each block needs.

### Block catalogue (v1)

| `ShotBlock` | Renders | Required fields | Typical trigger in a Spring talk |
|---|---|---|---|
| `SOURCE` | the real clip, transparent overlay layer, optional punch-in | `zoom?` | default — whenever he is showing code |
| `SLAM` | one huge word/phrase with a hit | `headline` | "virtual threads", "GraalVM", "1.0 is out" |
| `HIGHLIGHT` | sentence with a marker-box on the key word | `headline`, `subline` | "the *one* property you need" |
| `TERMINAL` | window chrome typing commands/output | `lines` | `./mvnw spring-boot:run`, `curl`, stack traces |
| `CODE` | syntax-highlighted snippet typing in | `lines` | an annotation, a `application.yml` key |
| `LOGO_WALL` | grid of ecosystem logos popping in | `logos` | "Boot, Data, Security, AI — all of it" |
| `DIAGRAM` | hub-and-spoke with packets and success ticks | `headline`, `lines` (node labels) | "one service, many clients" |
| `STAMP` | verdict stamp over a dimmed frame | `headline` | "DEPRECATED", "GA", "DON'T DO THIS" |
| `COUNTER` | number counting up to a stat | `headline` (the number), `subline` | "40% less memory", "Spring 7" |
| `IMAGE` | photo/screenshot with slow push-in | `imageQuery`, `headline?` | a person, a conference, a release page |
| `OUTRO` | stacked final line + handle + mascot | `headline` | last 2–3 s of every edit |

Adding a block later is additive: a new enum constant, a new engine function, a new validator rule.

### Validation and repair (`EditPlanValidator`)

Deterministic, unit-tested, runs before anything is stored:

1. Shots sorted, clamped to `[0, clipDuration]`, gaps filled with `SOURCE`, overlaps trimmed.
2. Every shot between `edit.minShotSeconds` (0.8) and `edit.maxShotSeconds` (6); longer `SOURCE` shots
   are allowed up to `edit.maxSourceSeconds` (12).
3. Non-`SOURCE` coverage capped at `edit.maxDesignedRatio` (0.6) — the footage stays the spine.
4. Required fields present per block; text trimmed to per-block length limits (a `SLAM` is ≤ 3 words).
5. `logos` filtered to the bundled library; unknown slugs dropped, not guessed.
6. `emphasis.at` snapped to the nearest real word start in the transcript; words that are not in the
   clip are dropped.
7. Last shot forced to `OUTRO`.

A plan that is invalid *after* repair (for example zero shots) fails the clip, not the video: the stage
writes a `SOURCE`-only fallback plan so the dashboard always has something to show, the same rule
`ContentGenerationStage` follows for blank content.

---

## 5. The Spring theme

`src/main/resources/edit/themes/spring.yml` — curated, versioned with the code, reviewed by a human:

```yaml
name: spring
accent: "#6DB33F"
topics:
  - match: ["virtual thread", "loom", "spring.threads.virtual"]
    prefer: [SLAM, DIAGRAM]
    logos: [openjdk, spring]
  - match: ["spring ai", "chatclient", "mcp", "rag", "embedding"]
    prefer: [TERMINAL, DIAGRAM, LOGO_WALL]
    logos: [spring, ollama]
  - match: ["graalvm", "native image", "aot"]
    prefer: [COUNTER, SLAM]
    logos: [graalvm, spring]
  - match: ["restclient", "httpexchange", "api versioning"]
    prefer: [CODE, HIGHLIGHT]
  - match: ["testcontainers", "serviceconnection"]
    prefer: [TERMINAL, LOGO_WALL]
    logos: [testcontainers, docker, postgresql]
vocabulary:
  - Spring Boot 4
  - Spring Framework 7
  - Spring AI
  - "@HttpExchange"
  - ProblemDetail
```

`ThemeCatalog` matches topics against the clip transcript and injects only the matching entries into the
planning prompt — a small, relevant context instead of the whole file. The theme is config
(`edit.theme: spring`), so a different creator is a different YAML file, not a code change.

The `vocabulary` list is also passed to ASR as the initial prompt (a follow-up, not v1) — it is the same
misrecognition problem the skill fixes with `fixes.json`.

---

## 6. Components

New package `ai.dashaun.kirinuki.edit`, following the existing layering: stage → service → clients.

| Class | Kind | Responsibility |
|---|---|---|
| `pipeline/stage/EditPlanningStage` | `PipelineStage` | `EDIT_PLANNING` → `edit-plans.json`; `@ConcurrencyLimit(1)` like scoring |
| `EditPlanner` | `@Component` | per clip: words in bounds + content title + theme → `EditPlanClient` → validator → fallback on failure |
| `EditPlanClient` | `@Component` | `ChatClient` structured output; `@Retryable(maxRetries = 2)`; model from `edit.planModel` via per-call `ChatOptions` |
| `EditPlanValidator` | `@Component` | §4 repair rules; pure, no IO |
| `ThemeCatalog` | `@Component` | loads theme YAML, matches topics |
| `DesignedEditService` | `@Service` | REST-facing: get/update plan (versioned), start render, regenerate one shot, status |
| `DesignedEditRunner` | `@Service` | the clip-level workflow in §7; skip-if-artifact-exists per step; virtual thread per render |
| `AssetResolver` | `@Component` | logos from the bundled library; images via `ImageSearchClient`; shared content-addressed cache |
| `ImageSearchClient` | `@Component` | `RestClient` against allowlisted hosts only (`edit.assets.allowedHosts`) |
| `FrameRenderer` | `@Component` | Playwright: stills, contact sheet, transparent frame sequence, cue export |
| `PreviewReviewer` | `@Component` | vision model reads the contact sheet → `List<QaFinding>` |
| `SoundDesigner` | `@Component` | Java port of the skill's synth: cues → `sfx.wav`, `music.wav`; seeded, byte-deterministic |
| `designed_edit` / `DesignedEditRepository` | JPA entity + `ListCrudRepository` | status, plan JSON, `lastError`, `@Version` |

Every new abstraction above maps one-to-one onto an existing pattern (`*Client` for model/HTTP,
`*Stage` for the spine, `*Service` for REST-facing logic). The only genuinely new shape is
`DesignedEditRunner` — **needs sign-off** (§13).

### The engine (front end, static)

`src/main/resources/edit/engine/` — vendored, served from the classpath to Chromium via a `file://`
copy in the clip's work directory, never over HTTP:

- `engine.html` — the stage, overlays (grain, vignette, flash), caption layer, progress bar, handle
- `engine.js` — reads `plan.json` + `words.json`, builds one paused GSAP timeline from block functions,
  exposes `window.renderAt(t)` and `window.SFX`
- `blocks/*.js` — one file per `ShotBlock`
- `vendor/gsap.min.js`, `vendor/fonts/*` — pinned, offline

This is the skill's `composition.html`, split so that the per-clip input is **data**, not a generated
file. Deterministic by construction: seeded grain, no `Math.random()`, no `Date.now()`.

`SOURCE` shots render with a transparent background and captions only; `FfmpegClient.renderDesigned`
composites the frame sequence **over the existing blurred-fill render of the clip**, applying the shot's
`zoom` to the underlying video with a `crop`/`scale` expression keyed on the shot window. That reuses the
standard renderer instead of trying to play video inside headless Chromium, which cannot seek
frame-accurately.

---

## 7. The designed render workflow

`POST /videos/{id}/review/{clipIndex}/designed-edit` → `202`, row moves to `QUEUED`.

| Step | Status | Artifact | What happens | Failure behaviour |
|---|---|---|---|---|
| 1 | `ASSETS` | `assets.json` | resolve logos + images, copy from cache, record credits | missing image → shot downgraded to `HIGHLIGHT` with its headline, recorded in `assets.json` |
| 2 | `PREVIEW` | `contact-sheet.png` | one still per shot at its densest moment | engine page error fails the step with the JS message in `lastError` |
| 3 | `VISION_QA` | `qa.json` | vision model reviews the sheet for clipped text, collisions, blank images, illegible code | findings → **one** automatic repair round (plan regenerated with findings as input, back to step 2); still failing → `NEEDS_ATTENTION`, shown in dashboard, render continues only on creator click |
| 4 | `FRAMES` | `frames/` | parallel pages render the transparent PNG sequence | page error fails the step |
| 5 | `SOUND` | `sfx.wav`, `music.wav` | engine exports cues; `SoundDesigner` synthesises | none expected; pure computation |
| 6 | `ENCODE` | `designed.mp4` | overlay frames on the blurred-fill clip, voice from source, SFX, ducked music, −14 LUFS, bitrate cap | ffmpeg exit code surfaced as today |
| 7 | `READY` | — | a verification frame is extracted from `designed.mp4` at the busiest shot and stored for the dashboard | — |

Status enum `DesignedEditStatus`: `QUEUED, ASSETS, PREVIEW, VISION_QA, NEEDS_ATTENTION, FRAMES, SOUND,
ENCODE, READY, FAILED`. Everything before `READY` except `NEEDS_ATTENTION` is resumable.

**Editing the plan invalidates downstream artifacts.** `PUT .../designed-edit/plan` bumps the version and
deletes `plan.json` onward, so the next render reuses nothing stale. Regenerating one shot deletes from
`PREVIEW` onward but keeps resolved assets for unchanged shots.

**Concurrency.** `@ConcurrencyLimit(edit.maxConcurrentRenders)` (default 1) on `DesignedEditRunner`. The
render is the heaviest thing the app does; it must not starve an ingest running beside it.

---

## 8. Dashboard

A **Designed edit** panel under each clip in `dashboard.html`:

- **Status chip** — `Plan ready` · `Rendering: FRAMES 412/900` · `Needs attention` · `Ready` · `Failed`
- **Shot table** — `#`, time range, block, headline/lines, with inline edit (versioned form, same
  `@Version` round-trip as content edits) and a per-row **Regenerate** button
- **Contact sheet** — thumbnail grid from `PREVIEW`, QA findings listed beside it
- **Actions** — `Render designed edit` · `Re-plan` · `Download designed.mp4`
- **Side-by-side player** — original clip and designed edit
- **Credits** — every image with source, author, licence, from `assets.json`
- **Approve as** — `Original` / `Designed` radio on the existing approve action

Progress is read from the `designed_edit` row, not by polling an artifact endpoint in a loop — the
project rule about console flooding applies to the dashboard too; the page refreshes on a modest
interval only while a render is active.

### REST additions (under the existing review root)

| Method | Path | Returns |
|---|---|---|
| `GET` | `/videos/{id}/review/{clipIndex}/designed-edit` | plan, status, version, findings, credits |
| `PUT` | `/videos/{id}/review/{clipIndex}/designed-edit/plan` | updated plan; `409` on version mismatch |
| `POST` | `/videos/{id}/review/{clipIndex}/designed-edit` | `202`, starts the render |
| `POST` | `/videos/{id}/review/{clipIndex}/designed-edit/shots/{shot}/regenerate` | regenerated shot |
| `POST` | `/videos/{id}/review/{clipIndex}/designed-edit/replan` | fresh plan |
| `GET` | `/videos/{id}/clips/{index}/designed` | the mp4 |

All errors are `KirinukiException` subclasses (`DesignedEditNotFoundException`,
`DesignedEditNotReadyException`, `StaleDesignedEditException`) so both advices stay single-handler.

---

## 9. Configuration

```yaml
kirinuki:
  pipeline:
    edit:
      enabled: true               # false → stage writes SOURCE-only plans; dashboard hides the panel
      theme: spring
      plan-model: ${EDIT_PLAN_MODEL:${CHAT_MODEL:llama4:latest}}
      vision-model: ${EDIT_VISION_MODEL:}   # empty → VISION_QA skipped, sheet shown to the creator instead
      plan-temperature: 0.4
      plan-max-tokens: 2048
      min-shot-seconds: 0.8
      max-shot-seconds: 6
      max-source-seconds: 12
      max-designed-ratio: 0.6
      fps: 30
      width: 1080
      height: 1920
      render-workers: 6
      max-concurrent-renders: 1
      music: synth                # synth | none | path to a local royalty-free file
      loudness-target: -14
      video-max-rate: 8M
      render-timeout: 30m
      assets:
        allowed-hosts: [commons.wikimedia.org, upload.wikimedia.org, spring.io]
        cache-ttl: 30d
```

`application-gpu.yml` overrides `render-workers: 12`, `max-concurrent-renders: 2`, and the model names
for the box. The model stays config, never Java — the rule scoring already follows.

---

## 10. Models on the GPU box

| Role | Needs | Candidate | Fits the 24GB 4090 budget? |
|---|---|---|---|
| Planning | reliable structured JSON, decent taste | the existing chat model (`llama4`) — try first | already in use |
| Vision QA | image input, short structured output | a multimodal build of the chat model, or a small VLM | to confirm on the box |

Two numbers from `CLAUDE.md` still apply if the endpoint is a local Ollama: **bound
`plan-max-tokens`** (a runaway plan costs minutes) and keep threads at half the cores on the laptop.
Planning is one call per clip; eight clips at ~20s each is ~3 minutes on the GPU box, far below scoring.

**Open question for Dashaun:** which models are loaded on the box, and whether one of them takes images.
Without a vision model the workflow still works — `VISION_QA` is skipped and the contact sheet becomes a
creator check before `FRAMES`.

---

## 11. Dependencies to approve

| Dependency | Why | Alternatives considered |
|---|---|---|
| `com.microsoft.playwright:playwright` | drive headless Chromium from Java for stills and frames | shelling out to Node via `ProcessRunner` (adds a second runtime to the image); pure ffmpeg (cannot draw these scenes); JavaFX WebView (not headless-capable, poor CSS support) |
| GSAP 3 (vendored JS, not Maven) | the animation timeline the engine is built on | CSS animations (not seekable per frame); Web Animations API (workable, but rewriting every block for no gain). GSAP is free for commercial use since 2025 |
| Bricolage Grotesque, JetBrains Mono (vendored fonts) | brand typography inside the engine | system fonts (EXPERIMENTS notes the brand font is not installed for libass either) |

No new Java library for audio: WAV is written by hand from `short[]` PCM, as the Python synth does.

---

## 12. Decisions to record

- **ADR-0006 — Designed edits render in headless Chromium.** Context: ffmpeg cannot draw
  content-dependent scenes. Decision: data-driven engine + Playwright, frames composited over the existing
  render. Consequences: Debian runtime image, Chromium in the image, render time per clip.
- **ADR-0007 — Outbound asset fetching.** Images are fetched from an allowlist and cached. ADR-0001 is
  **not** affected — no transcript, audio or video leaves the machine; the only outbound payload is an
  image search query derived from a shot's `imageQuery`. Recorded because it is the first outbound call
  that is not the model endpoint, and `allowed-hosts: []` turns it off entirely.
- **PRD amendment** — add M9 to §6; note under NG1 that designed edits are proposed, field-edited plans,
  not a timeline.
- **FRD** — new `FR-EDIT` section (below).

### FR-EDIT (draft)

| ID | Requirement |
|---|---|
| FR-EDIT-1 | The system MUST generate an edit plan for every rendered clip from the clip's word-level transcript, its generated title and the configured theme. |
| FR-EDIT-2 | An edit plan MUST only reference blocks from the block catalogue and MUST be validated and repaired before it is stored. |
| FR-EDIT-3 | A failed plan MUST fall back to a source-only plan; it MUST NOT fail the video. |
| FR-EDIT-4 | The creator MUST be able to edit, regenerate and re-plan shots, with optimistic locking. |
| FR-EDIT-5 | Designed renders MUST be started per clip by the creator and MUST be resumable after a restart. |
| FR-EDIT-6 | Rendering MUST be deterministic per `(videoId, clip, plan version)`. |
| FR-EDIT-7 | Every external image MUST come from an allowlisted host and MUST carry its source and licence into the dashboard. |
| FR-EDIT-8 | The original clip MUST remain available; approval MUST record which variant is published. |
| FR-EDIT-9 | The designed edit's audio MUST be normalised to the configured loudness target. |

---

## 13. Phased delivery

Each phase ends with something the creator can see, and each follows the project's "prove it in a
script, then port" rule from `EXPERIMENTS.md`.

### Phase 0 — Harness (no app changes)

`scripts/designed-edit.py`, stdlib + the vendored engine, same shape as `render-variant.py`:

```bash
scripts/designed-edit.py plan   --clip 1          # calls OPENAI_BASE_URL, writes storage/_scratch/designed/clip-1/plan.json
scripts/designed-edit.py render --clip 1          # stills → contact sheet → frames → sound → designed.mp4
scripts/designed-edit.py render --clip 1 --plan my-plan.json
```

Fixed test clip: `5a09516b` clip 1 — the same control EXPERIMENTS uses.

**Exit criteria:** three clips planned by the local model and rendered; Dashaun watches them on a phone
beside the originals and says which is better. **If the local model's plans are not good enough, stop
here** — tune the prompt and theme in the harness, not in Java. This is the go/no-go for the milestone.

### Phase 1 — Planning in the pipeline

`EDIT_PLANNING` status, `EditPlanningStage`, `EditPlanner`, `EditPlanClient`, `EditPlanValidator`,
`ThemeCatalog`, `spring.yml`, `edit-plans.json`, read-only plan table in the dashboard.

**Exit:** a fresh ingest parks at `READY_FOR_REVIEW` with a plan per clip visible; a planning failure
shows a source-only plan, not a stalled video.

### Phase 2 — Render workflow

`designed_edit` table (`V5__designed_edit.sql`), `DesignedEditRunner`, `FrameRenderer`, `SoundDesigner`,
`FfmpegClient.renderDesigned`, engine resources, "Render designed edit" button, download, recovery,
Debian `Dockerfile`.

**Exit:** a clip renders end to end from the dashboard; killing the app mid-`FRAMES` and restarting
resumes from `FRAMES`, not from `ASSETS`.

### Phase 3 — Assets and credits

`AssetResolver`, `ImageSearchClient`, shared cache, `IMAGE` block, credits panel, ADR-0007.

**Exit:** a clip mentioning a named person or release gets a real image with its licence shown;
`allowed-hosts: []` renders the same clip with the image shots downgraded.

### Phase 4 — Plan editing

`PUT plan`, per-shot regenerate, re-plan, invalidation rules, `409` on stale edits, `variant` on approval
(`V6__clip_review_variant.sql`).

**Exit:** edit one headline, re-render, and only `PREVIEW` onward is recomputed.

### Phase 5 — Vision QA

`PreviewReviewer`, `qa.json`, one automatic repair round, `NEEDS_ATTENTION`.

**Exit:** a deliberately overlong `SLAM` headline is caught and repaired before `FRAMES` starts.

---

## 14. Testing

Following the house rules: unit tests beside the class; `*IntegrationTest` under failsafe for anything
crossing HTTP, the database or a browser; persistence is never mocked.

| Test | Kind | Proves |
|---|---|---|
| `EditPlanValidatorTest` | unit | every repair rule in §4, one behaviour per test (`should_fill_gap_with_source_when_shots_do_not_touch`) |
| `ThemeCatalogTest` | unit | topic matching, prompt excerpt stays small |
| `SoundDesignerTest` | unit | same cues → identical bytes; a cue at `t` lands at sample `t × 48000` |
| `EditPlannerTest` | unit | fallback plan on client failure; emphasis snapped to real words |
| `EditPlanningStageIntegrationTest` | integration, Postgres Testcontainer | stage writes `edit-plans.json` and advances status; the model endpoint is the only `@MockitoBean` (an out-of-process collaborator) |
| `DesignedEditRepositoryIntegrationTest` | integration | `@Version` conflict surfaces as `409` through the controller |
| `DesignedEditRunnerIntegrationTest` | integration | resume skips committed steps; a failed step records `lastError` |
| `FrameRendererIntegrationTest` | integration, needs Chromium | a fixture plan renders 3 frames; the grain is identical across two renders of frame 0 |
| `DesignedEditControllerIntegrationTest` | integration, `RestTestClient` | every endpoint in §8, including `202` and `409` |
| `FfmpegClientDesignedIntegrationTest` | integration, needs ffmpeg | output duration equals clip duration; integrated loudness within 1 LU of target |

Browser- and ffmpeg-dependent tests are skipped with a clear reason when the binary is absent, the same
way the app surfaces `ToolNotAvailableException`.

---

## 15. Observability

- Stage: `kirinuki.stage` observation for `EDIT_PLANNING`, as every stage today.
- Render: `kirinuki.edit.render` parent with one child per workflow step; frame count and frames/second
  as a timer + gauge, so "how long does a designed edit take on the 4090" is a Grafana panel.
- Model calls: already covered by Spring AI's `gen_ai_*` metrics; plan and QA calls carry an
  `edit.role=plan|vision` low-cardinality key.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Local model plans are dull or repetitive | the feature is pointless | Phase 0 go/no-go; theme `prefer` lists; few-shot examples from real Spring clips in the prompt |
| Structured output drifts on a wide schema | invalid plans | flat `Shot`, validator repair, source-only fallback |
| Render time on CPU | laptop runs take many minutes per clip | on-demand per clip, `max-concurrent-renders: 1`, judge on the GPU box as `CLAUDE.md` already says |
| Code on `SOURCE` shots still small | legibility | punch-in `zoom` per shot now; FR-RENDER-6 ROI crop later benefits both variants |
| Trademark use of Spring and partner logos | client/legal exposure | curated bundled library only, reviewed once; no logo fetched at runtime |
| Image licences | takedowns | allowlist + stored licence + credits panel; creator approves before publish |
| Image-size growth of the Docker image (Chromium ≈ 300 MB) | slower pulls | one-time cost; Chromium installed in a cached layer |
| Disk: PNG frame sequences (~1–2 GB per minute of edit) | fills disk | delete `frames/` after `ENCODE` commits; keep `designed.mp4`, `plan.json`, `contact-sheet.png` |

---

## 17. Needs sign-off before Phase 1

1. **Go/no-go from Phase 0** — watched on a phone.
2. **`com.microsoft.playwright:playwright`** as a dependency.
3. **`DesignedEditRunner`** as a new clip-level workflow abstraction (rather than widening `PipelineStage`
   to clip scope, which would change the interface every existing stage implements).
4. **`EDIT_PLANNING` inserted into `PipelineStatus`** — changes the flow for videos mid-pipeline.
5. **Debian runtime image.**
6. **Which models** Dashaun runs, and whether one takes images.
7. **Spring logo usage** — who confirms the bundled library is acceptable.
