# Kirinuki — Product Requirements Document (PRD)

**Status:** Draft · design phase
**Owner:** your_javaguy
**Related:** [FRD](FRD.md) · [Architecture](ARCHITECTURE.md)

---

## 1. Problem

Long-form video (tutorials, streams, talks) contains many short, high-value moments — a crisp
explanation, a strong hook, a laugh, a demo payoff. Turning those into short-form clips for TikTok,
Reels, Shorts, LinkedIn, and X is where most of the reach is, but doing it by hand is slow and
repetitive: scrub the timeline, guess the best segments, cut them, reframe to vertical, add subtitles,
then write a different caption for every platform. A one-hour video can be an afternoon of editing for
a handful of clips.

**The cost isn't just time — it's throughput.** Creators publish a fraction of the short-form they
could, because the per-clip effort is high and the "which moment is actually good?" decision is fatiguing.

## 2. Vision

Drop in a long video; get back a ranked set of polished, vertical, subtitled clips — each with
per-platform captions and metadata — ready to review and publish. The creator's job shrinks to
**judgment** (approve/edit/reject) instead of **labor** (find/cut/reframe/caption).

## 3. Target users

| Persona | Description | Primary need |
|---|---|---|
| **Solo technical creator** (primary) | Records long tutorials/streams; posts to multiple platforms | Maximize clips shipped per video with minimal editing time |
| **Small content team** (secondary) | An editor + creator splitting the workload | Automate the mechanical steps; keep human control over final selection |
| **Repurposing-focused creator** (secondary) | Has a back-catalog of long videos | Batch-mine existing content for clips |

## 4. Goals & non-goals

### Goals
- **G1** — Automatically surface the best candidate moments in a long video, ranked, with a reason.
- **G2** — Produce finished vertical clips (cut, reframed, subtitled, watermarked, thumbnailed).
- **G3** — Generate per-platform captions/titles/tags for each clip.
- **G4** — Keep the human in control: nothing publishes without explicit approval.
- **G5** — Make the pipeline resilient and resumable — a failure or restart never loses completed work.

### Non-goals (for now)
- **NG1** — A full non-linear video editor. Kirinuki proposes and renders; it is not a timeline editor.
- **NG2** — Real-time / live-stream clipping. Kirinuki works on finished recordings (batch).
- **NG3** — Long-form editing (chaptering full videos, multi-cam switching, etc.).
- **NG4** — Guaranteed auto-publishing to every platform on day one — publishing is optional and phased.
- **NG5** — Committing to a *specific* AI model per role in this document (deferred — the hosting axis
  is decided: local; see [Architecture](ARCHITECTURE.md) / [ADR-0001](DECISIONS.md)).

## 5. Success metrics

| Metric | Intent | Target (initial) |
|---|---|---|
| **Clip acceptance rate** | Are the surfaced moments actually good? | ≥ 60% of top-ranked clips approved without re-selection |
| **Time-to-first-clip** | Is it faster than manual? | A creator gets reviewable clips from a 1-hour video without manual editing effort |
| **Edits per accepted clip** | Is the output close to publish-ready? | Caption/title edited on ≤ 1 of 3 accepted clips |
| **Pipeline resumability** | Does work survive failure? | 100% of completed stages reused (never recomputed) after a restart |
| **Clips shipped per source video** | The throughput win | Materially more than the creator's manual baseline |

> The single most important metric is **clip acceptance rate** — it measures the one genuinely hard
> part (moment discovery quality). Everything else is mechanical.

## 6. Scope & phasing

Delivery is milestone-based; each milestone is independently useful.

*Status as of 2026-08-26.*

| Phase | Status | Deliverable | Value |
|---|---|---|---|
| **M1 — Prove the core** | ✅ done | YouTube URL → yt-dlp download → transcript → candidate moments → AI scoring → deterministic cut → **download** | Validates clip-discovery *quality* before any heavy infrastructure |
| **M2 — Durability** | ✅ done | Local-storage source of truth + per-stage idempotency/resume | Nothing is lost or recomputed |
| **M3 — Full analysis** | ◐ partial | All feature extractors (scenes, OCR, audio, visual) + parallel fan-out/in | Richer, better-justified candidate scoring |
| **M4 — Content & review** | ✅ done | Per-platform content generation + review dashboard (edit/approve/regenerate) | End-to-end creator workflow |
| **M5 — Accounts & auto-ingest** | ☐ not started | Auth + multi-user + OAuth channel connect with **scheduled polling** of new uploads (YouTube Data API detects → yt-dlp downloads) | Hands-free ingest; multi-user; batch back-catalog |
| **M6 — Publishing** | ☐ not started | Optional per-platform publishing, one platform at a time | Convenience layer; opt-in |
| **M7 — Series packaging** *(future)* | ☐ not started | Group clips into ordered, themed release series ("Part 1/2/3") | Ship a narrative sequence, not just loose clips |
| **M8 — Analytics learning loop** *(future)* | ☐ not started | Pull per-post performance stats → recommend what to make/post next → feed priors back into scoring | The pipeline learns what the audience loves |

**M3 is partial** — scene and audio extraction ship (`SceneExtractionStage`, `AudioExtractionStage`,
fanned out with transcription on `FEATURE_EXTRACTION`); OCR and visual analysis do not exist yet. They
are also the prerequisite for content-aware reframing (FR-RENDER-6).

**M4 has one open gap** — FR-CONTENT-2 asks for platform-*shaped* variants ("X tweet + thread
suggestion", "YouTube title + description + SEO tags"), but `PlatformVariant` is a single generic
`(platform, title, caption, hashtags, callToAction)` applied to every platform.

**M5 is the gate on deploying anything.** There is no authentication of any kind today: every REST
endpoint and the whole dashboard are open.

## 7. Key product decisions

- **Human-in-the-loop by default.** Auto-publish is **off**; the pipeline parks at review indefinitely.
- **Download-first.** Even before any publishing integration exists, the product is fully useful:
  the creator downloads finished clips and posts manually.
- **Quality over volume.** Better to surface 8 genuinely strong clips than 30 mediocre ones.
- **Deterministic where possible.** Cutting, reframing, and boundary snapping are deterministic;
  the model is reserved for judgment (which moments, what to say about them).

## 8. Assumptions

- Source videos are finished YouTube recordings the user owns or has rights to repurpose (typically the
  creator's own uploads).
- Users have at least one target platform in mind and will review before posting.
- The "best moment" is judgeable from transcript + scene + audio/visual signals combined.

## 9. Risks & open questions

| Risk / question | Why it matters | Mitigation / owner |
|---|---|---|
| **Moment-discovery quality** | If top clips aren't good, nothing else matters | Validate in M1 on real videos before building infra |
| **Local inference throughput** | Hosting is decided (local — [ADR-0001](DECISIONS.md)); privacy solved and no per-call cost, but throughput is hardware/VRAM-bound and local models are less reliable at strict structured output | Budget VRAM / keep models warm; schema-validate + retry + constrained decoding for scoring |
| **YouTube ingest (yt-dlp / ToS)** | yt-dlp can break with site changes; only rights-holder content should be ingested | Pin the yt-dlp version; ingest videos-you-own only |
| **Publishing API constraints** | Platform approval gates / paid tiers are large hidden work | Phase publishing last; ship download-only first |
| **Storage growth** | Raw + normalized + frames balloon fast | Retention/cleanup policy + sparse frame sampling |
| **Reframing quality (vertical crop)** | Bad auto-crop cuts off the speaker/slide | Track as a quality metric; allow manual crop override in review |
| **Learning-loop cold-start** *(future)* | No performance priors until enough posts accumulate | Ship discovery without priors; feed them in once data exists |

## 10. Out of the box, a user can…

1. Submit a YouTube URL (their own uploads / rights-holder content).
2. Wait while Kirinuki downloads, analyzes, and cuts.
3. Open a review dashboard showing ranked clips — each with preview, transcript, title, caption,
   hashtags, suggested platforms, and a confidence score.
4. Edit, approve, reject, download, or regenerate any clip's title/caption.
5. Optionally enable publishing per platform.
