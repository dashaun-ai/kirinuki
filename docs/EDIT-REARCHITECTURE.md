# Edit Re-architecture Plan

Multi-speaker, code-aware, kinetic edit pipeline for Kirinuki.

## Problem

The current designed-edit feature assumes a single speaker and renders most shots as text-on-color slides. That breaks for the real content we want to process:

- 2–3 speakers in conversation.
- Code/terminal/screen sharing visible in the source.
- Conceptual sections (e.g. "Java 8 → 9 → 10 → 11") that need kinetic motion graphics.

We need a director-style editor that chooses a different visual treatment for each beat.

---

## Editorial model

For every shot the director picks one of these types:

| Type | Use when | Visual |
|---|---|---|
| `KINETIC` | Sequence, history, list, abstract concept (e.g. version evolution) | Animated text/icons/timeline on branded background |
| `CODE` | Code/terminal/output is on screen and relevant | Code/screen fills frame; speaker as small pip or hidden |
| `SPEAKER` | Pure explanation/conversation, no relevant screen content | Active speaker in vertical crop |
| `DIALOGUE_2UP` | Two speakers going back and forth | Split or switch on turn |
| `DIALOGUE_3UP` | Three speakers | Active speaker large, others as thumbnails or rotate |
| `MIXED` | Speaker explaining code while visible | Speaker top / code bottom, or speaker pip on code |

The director also decides:

- Who is speaking when.
- Whether the screen content is worth showing.
- Whether a concept should be animated.
- Speaker balance (avoid one person dominating).

---

## Architecture

```
source.mp4
    │
    ▼
┌─────────────────────┐
│ Scene Analysis      │  ← Chunk 1
│ - face tracking     │
│ - speaker diarization│
│ - screen/code region │
│ - screen changes    │
└──────────┬──────────┘
           │ scene.json
           ▼
┌─────────────────────┐
│ Director / Planner  │  ← Chunk 2
│ - shot types        │
│ - kinetic specs     │
│ - speaker balance   │
└──────────┬──────────┘
           │ edit-plan.json
           ▼
┌─────────────────────┐
│ Layout Renderers    │  ← Chunks 3 & 4
│ - ffmpeg layouts    │
│ - Kinetic renderer  │
└──────────┬──────────┘
           │ segments/
           ▼
┌─────────────────────┐
│ Mixer               │  ← Chunk 5
│ - concat            │
│ - transitions       │
│ - audio bed         │
└──────────┬──────────┘
           ▼
    designed.mp4
```

---

## Chunk 1: Scene analysis

**Goal**: produce `scene.json` for every clip.

### 1.1 Extend face tracking

Current `FaceExtractor` runs face detection on one probe frame. Extend it to:

- Sample frames at a fixed interval across the clip (e.g. 2 fps).
- Run `res10_300x300_ssd_iter_140000` on each frame.
- Track each detected face across frames using IOU or simple centroid clustering.
- Output a list of `FaceTrack` objects with face bounding boxes over time.

Files to touch:

- `src/main/java/ai/dashaun/kirinuki/vision/FaceExtractor.java`
- `src/main/java/ai/dashaun/kirinuki/vision/FaceTrack.java` (extend if needed)
- `src/main/java/ai/dashaun/kirinuki/vision/FaceSample.java` (extend if needed)

### 1.2 Speaker tracking

Map transcript words to face tracks using mouth-motion heuristics. A speaker without a visible face is still tracked by transcript timing.

Output:

```json
{
  "speakers": [
    {"id": "A", "faceTrack": "track-1"},
    {"id": "B", "faceTrack": "track-2"}
  ],
  "activeSpeaker": [
    {"from": 0.0, "to": 8.5, "speaker": "A"},
    {"from": 8.5, "to": 14.0, "speaker": "B"}
  ]
}
```

New files:

- `src/main/java/ai/dashaun/kirinuki/vision/SpeakerTracker.java`
- `src/main/java/ai/dashaun/kirinuki/vision/SpeakerActivity.java`

### 1.3 Screen/code region detection

Detect the screen region and significant screen changes:

- Convert frames to grayscale.
- Frame-difference in a central/lower region to detect screen changes.
- Use edge density or OCR presence to confirm the region contains text/code.
- Output a stable `screenRegion` rectangle and a list of `screenChanges` timestamps.

Output:

```json
{
  "screenRegion": {"x": 0.05, "y": 0.25, "w": 0.9, "h": 0.65},
  "screenChanges": [12.5, 18.3, 34.0],
  "hasCode": true
}
```

New files:

- `src/main/java/ai/dashaun/kirinuki/vision/ScreenAnalyzer.java`
- `src/main/java/ai/dashaun/kirinuki/vision/ScreenAnalysis.java`
- `src/main/java/ai/dashaun/kirinuki/vision/ScreenRegion.java`

### 1.4 Scene analysis stage

Add a new pipeline stage `SceneAnalysisStage` that runs after clip rendering and before edit planning:

- Reads the source video and transcript.
- Runs face tracking, speaker diarization, and screen analysis.
- Writes `scene.json` per clip or per video.

Files to touch:

- `src/main/java/ai/dashaun/kirinuki/pipeline/Artifacts.java` (add `SCENE`)
- `src/main/java/ai/dashaun/kirinuki/pipeline/PipelineStatus.java` (add `SCENE_ANALYSIS`)
- `src/main/java/ai/dashaun/kirinuki/pipeline/stage/SceneAnalysisStage.java`
- `src/main/java/ai/dashaun/kirinuki/pipeline/PipelineOrchestrator.java`

---

## Chunk 2: Director-style edit planning

**Goal**: richer edit plans with shot types and kinetic specs.

### 2.1 Extend data model

Extend `Shot` and `EditPlan`:

```java
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
    String transition,
    ShotType type,           // new
    String speaker,          // new
    List<String> speakers,   // new
    KineticSpec kinetic,     // new
    Region focus             // new
) {}
```

New enum `ShotType` and record `KineticSpec`:

```java
public enum ShotType {
    KINETIC, CODE, SPEAKER, DIALOGUE_2UP, DIALOGUE_3UP, MIXED
}

public record KineticSpec(
    String style,          // timeline, list, definition, comparison
    String title,
    List<KineticItem> items
) {}

public record KineticItem(String label, String value, String detail) {}
```

Files to touch:

- `src/main/java/ai/dashaun/kirinuki/edit/Shot.java`
- `src/main/java/ai/dashaun/kirinuki/edit/ShotBlock.java`
- `src/main/java/ai/dashaun/kirinuki/edit/EditPlan.java`
- `src/main/java/ai/dashaun/kirinuki/edit/KineticSpec.java` (new)
- `src/main/java/ai/dashaun/kirinuki/edit/ShotType.java` (new)

### 2.2 Rewrite the director prompt

Update `EditPlanClient` prompt to:

- Receive clip transcript + `scene.json`.
- Decide shot type for each beat.
- Build kinetic specs for conceptual sections.
- Enforce speaker balance.
- Avoid covering code with text.

Example output structure:

```json
{
  "shots": [
    {
      "in": 12.5,
      "out": 18.0,
      "type": "KINETIC",
      "kinetic": {
        "style": "timeline",
        "title": "Java evolution",
        "items": [
          {"label": "Java 8", "value": "2014"},
          {"label": "Java 9", "value": "2017"},
          {"label": "Java 10", "value": "2018"}
        ]
      }
    },
    {
      "in": 18.0,
      "out": 26.0,
      "type": "CODE",
      "speaker": "A",
      "focus": {"x": 0.1, "y": 0.2, "w": 0.8, "h": 0.6}
    }
  ]
}
```

### 2.3 Validator rules

Extend `EditPlanValidator`:

- Reject code shots if `hasCode` is false.
- Enforce speaker air-time balance.
- Convert invalid kinetic shots to `SPEAKER` fallback.
- Ensure captions do not overlap code region.

---

## Chunk 3: Layout engine (ffmpeg)

**Goal**: render source/code/speaker segments with smart layouts.

### 3.1 Layout renderer

Create a `LayoutRenderer` that handles:

- `SPEAKER`: vertical crop following active speaker face.
- `DIALOGUE_2UP`: split frame or switch on turn.
- `DIALOGUE_3UP`: active speaker large, others as thumbnails.
- `CODE`: code region large, speaker pip.
- `MIXED`: speaker top, code bottom.

Use the existing `FramingPlan`/`SpeakerSegment` work in `DominantSpeakerSelector` as the basis for speaker cropping.

New files:

- `src/main/java/ai/dashaun/kirinuki/edit/LayoutRenderer.java`
- `src/main/java/ai/dashaun/kirinuki/edit/SpeakerLayout.java`
- `src/main/java/ai/dashaun/kirinuki/edit/CodeLayout.java`

### 3.2 Caption placement

Burn captions in safe margins only:

- If code is in lower half, captions go top.
- If code is in upper half, captions go bottom.
- Never cover the screen region.

### 3.3 Motion

Add subtle face-tracking pan/zoom instead of static crops.

---

## Chunk 4: Kinetic renderer

**Goal**: animated segments for conceptual sections.

### 4.1 Kirinuki-to-kinetic-skill bridge

Build a service that:

- Maps Kirinuki theme/config to a kinetic skill brand kit.
- Extracts the audio slice for a kinetic shot.
- Writes a kinetic script from `KineticSpec`.
- Runs the kinetic skill scripts and returns the rendered `.mp4`.

New files:

- `src/main/java/ai/dashaun/kirinuki/edit/KineticRenderer.java`
- `src/main/java/ai/dashaun/kirinuki/edit/KineticSpec.java`
- `src/main/java/ai/dashaun/kirinuki/edit/KineticItem.java`

### 4.2 Kinetic skill setup

Run `setup.sh` from the installed skill once per environment and cache the ready state.

### 4.3 Render workflow per kinetic shot

1. Extract audio slice from source clip.
2. Build brand kit in a temp work dir.
3. `transcribe.py` → `build_captions.py` → `fill_template.py` with kinetic spec.
4. `render.js` stills/frames → `mix-encode.sh` → `mp4`.
5. Copy result back to Kirinuki storage.

---

## Chunk 5: Mixer

**Goal**: combine all segments into one final designed video.

1. Collect ffmpeg-rendered segments and kinetic-rendered segments.
2. Write a concat list in time order.
3. Add cross-fade transitions where shot type changes.
4. Add a shared audio bed:
   - Voice from source clip.
   - Optional music/SFX from the kinetic skill or a simple loop.
   - Loudnorm to -14 LUFS.

Files to touch:

- `src/main/java/ai/dashaun/kirinuki/edit/DesignedEditRenderer.java` (refactor to orchestrate)
- `src/main/java/ai/dashaun/kirinuki/edit/EditMixer.java` (new)

---

## Chunk 6: Dashboard and operations

**Goal**: make the new pipeline observable and controllable.

1. Show shot types and layouts in the dashboard.
2. Add a "Re-render designed edit" action.
3. Add config flags:
   - `kirinuki.pipeline.edit.renderer=ffmpeg|kinetic|hybrid`
   - `kirinuki.pipeline.edit.multi-speaker=true|false`
4. Add health/timeouts for long kinetic renders.

Files to touch:

- `src/main/resources/templates/dashboard.html`
- `src/main/java/ai/dashaun/kirinuki/dashboard/DashboardController.java`
- `src/main/java/ai/dashaun/kirinuki/config/KirinukiPipelineProperties.java`

---

## Chunk 7: End-to-end validation

**Goal**: prove it works on real content.

1. Run the new pipeline on a multi-speaker code video.
2. Produce three outputs for comparison:
   - Source-only clip.
   - Current designed edit.
   - New hybrid edit.
3. Tune prompts, layouts, and thresholds based on review.

---

## Current state

- OpenCV face detection and tracking across the clip outputs `FaceTrack` timelines.
- Speaker tracking maps face motion + transcript words to `SpeakerActivity`.
- Screen/code region detection runs in Java and outputs `ScreenAnalysis`.
- Scene analysis stage produces `scene.json`.
- Edit planning supports shot types, kinetic specs, and validation.
- Layout renderers exist for `SPEAKER`, `CODE`, and `MIXED`.
- Kinetic bridge is implemented in Java and calls the installed skill scripts directly.
- No Python scripts live in the Kirinuki repo.

## Next tasks

- **Chunk 3 (continued)**: implement `DIALOGUE_2UP` and `DIALOGUE_3UP` layout renderers.
- **Chunk 5**: extract `EditMixer` and add transitions/audio bed.
