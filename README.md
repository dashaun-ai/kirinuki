# Kirinuki

Turn long-form video into polished, ready-to-publish short-form clips.

Kirinuki ingests a long recording (submit a YouTube URL), discovers the best moments, cuts them into
vertical clips with burned-in subtitles, and generates per-platform captions and metadata.
**Publishing is optional** — the core product finds the moments and delivers the assets; posting is a
convenience layered on top.

> **Status:** design phase. No code yet. The system is **fully local** — YouTube source via yt-dlp,
> local filesystem storage, local Postgres, and **local inference models** (hosting decided; exact
> per-role model still tuned — see [Open Decisions](docs/ARCHITECTURE.md#open-decisions)).
> **Planned next:** packaging clips into themed release *series*, and an *analytics learning loop* that
> learns what the audience loves and recommends what to make next.

---

## Documentation

| Document | What it covers |
|---|---|
| [Product Requirements (PRD)](docs/PRD.md) | Why we're building this, who it's for, goals, scope, success metrics |
| [Functional Requirements (FRD)](docs/FRD.md) | What the system must do — features, inputs/outputs, per-stage behavior, acceptance criteria |
| [Architecture](docs/ARCHITECTURE.md) | How it's built — components, workflow, pipeline stages, storage, diagrams |
| [Decisions (ADRs)](docs/DECISIONS.md) | Significant, hard-to-reverse choices — starting with model & hosting |

**Read in this order:** PRD (the *why*) → FRD (the *what*) → Architecture (the *how*).

---

## In one picture

```
YouTube URL ─▶ Media Prep ─▶ ┌ Transcript ┐
   (yt-dlp)                  │ Scenes     │
                             │ OCR        ├─▶ Candidates ─▶ AI Scoring ─▶ Render ─▶ Review ─▶ (Publish)
                             │ Audio      │
                             └ Visual     ┘
```

Deterministic media + local inference, coordinated by a Spring-native orchestrator. The local
filesystem is the shared source of truth; every expensive stage is idempotent and independently
retryable.

---

## Guiding principles

1. Each ingested video is a **long-running workflow** that can pause for human review.
2. **Local storage is the source of truth**; the database carries only metadata and pointers.
3. Every expensive stage is **idempotent and independently retryable**, keyed by `(videoId, stage)` —
   which doubles as the resume mechanism.
4. **AI is one service among many** — deterministic stages do most of the work; the local model is
   invoked only where genuine reasoning is required, never to drive the control flow.
