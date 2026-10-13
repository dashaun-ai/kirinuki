# Kirinuki render experiments

Things to try and judge, distinct from `docs/REVIEW.md` (which is the defect list). Everything here is
a change we can evaluate by looking at or listening to the output — no experiment lands until you have
watched the before and after.

Measured on 2026-08-16 against the three runs in `storage/`. Numbers below are from those files, not
from the docs.

---

## Preflight — the toolchain is not the constraint

```
ffmpeg 6.1.1-3ubuntu5
--enable-libass --enable-libfreetype --enable-libfontconfig --enable-libfribidi --enable-libharfbuzz
filters present: ass, subtitles, loudnorm, ebur128, zoompan, drawtext, drawbox, overlay
```

Every filter each experiment below needs is already compiled in. Nothing here requires a new binary, a
new dependency, or a rebuild of ffmpeg.

---

## Correction: the source is 1080p, not 720p

`AGENTS.md` says "the source is a 720p screen-share, so a 9:16 crop gives 405×720 upscaled to
1080×1920." That is stale. All three sources on disk are **1920×1080**:

| Run | Source | fps | Duration |
|---|---|---|---|
| `5a09516b` | 1920×1080 h264 | 60 | 762s |
| `e8556bb1` | 1920×1080 h264 | 29.97 | 982s |
| `ed94f058` | 1920×1080 h264 | 29.97 | 982s |

A 9:16 centre crop of 1080p is 607×1080 → 1.78x upscale to fill the frame, not the 2.67x the docs
imply. The argument against centre-cropping still holds — it slices code in half, which is a content
problem, not a resolution one — but the resolution headroom is better than recorded. Worth fixing in
`AGENTS.md` either way.

### The frame budget we actually have

At `render.zoom: 1.0`, `verticalFilter` scales the foreground to `1080:-2` → **1080×608**, centred on a
1080×1920 canvas.

```
 y=0     ┌──────────────────┐
         │                  │  656px — empty blurred fill
 y=656   ├──────────────────┤
         │   video 1080×608 │  608px — 32% of the frame
 y=1264  ├──────────────────┤
         │                  │  336px — empty blurred fill
 y=1600  ├──────────────────┤
         │  gradient + caps │  320px — geq alpha ramp, captions at marginV=200
 y=1920  └──────────────────┘
```

**1312px of 1920 — 68% of the frame — carries no information.** That reframes most of what follows:
the blurred fill is not just a compromise, it is unused real estate. The top 656px in particular is
completely untouched today.

---

## X1 — Word-level caption highlighting

**Highest ratio in this document.** You already have per-word timings in `Word(text, start, end)`, and
`SubtitleWriter` is a `StringBuilder`. This is the single biggest visual differentiator on short-form
and it needs no filtergraph change at all.

Current output is one static `Dialogue` per 3 words, style `Default`, no override tags.

**X1a — native karaoke.** Emit `{\k<centis>}` per word within a caption. Blocker to know about: the
`[V4+ Styles]` `Format:` line in `header()` declares `PrimaryColour, OutlineColour, BackColour` and
**omits `SecondaryColour`** — `\k` highlights *from* secondary *to* primary, so it does nothing until
that field is added to both the `Format:` and `Style:` lines. Use `\kf` for a sweep fill instead of an
instant switch.

**X1b — per-word pop.** Emit one `Dialogue` per word covering that word's span, with the active word
carrying an inline `{\c&H..&\fscx110\fscy110}` override and the neighbours dimmed. More lines, total
control over colour and scale, no Style surgery. This is the look most short-form editors ship.

Implementation note for both: words are currently joined with `String::concat` then `.strip()`, which
works because whisper tokens carry leading spaces. Per-word emission has to preserve that spacing
deliberately rather than by accident.

**Judge:** render clip 1 of `5a09516b` three ways — current, X1a, X1b — and watch them back to back on
a phone, not a monitor. **Cost:** ~30 lines in one existing file. **Risk:** none; ASS is regenerated
per clip and nothing else reads it.

## X2 — Caption placement and density

Pure config, zero code. `wordsPerCaption: 3`, `subtitleSize: 78`, `subtitleMarginBottom: 200`,
`subtitleUppercase: false` are all already bound properties.

Captions currently sit inside the gradient band at the bottom. Worth sweeping: 2 vs 3 vs 4 words per
caption, size 78 vs 96, uppercase on/off, and margin values that place captions in the **lower dead
zone under the video** (around `marginV: 380`) rather than at the frame edge.

**Judge:** one clip, six config variants, pick by eye. **Cost:** nothing — it is `application-local.yml`.
Do this one first because it costs a render each and may make X1 easier to evaluate.

## X3 — Loudness normalization

**Measured, not theoretical.** Integrated loudness of shipped clips:

| Clip | Integrated |
|---|---|
| `5a09516b` clip-1 | **-27.3 LUFS** |
| `5a09516b` clip-4 | **-26.5 LUFS** |
| `e8556bb1` clip-1 | **-23.5 LUFS** |
| `e8556bb1` clip-3 | **-24.3 LUFS** |

Social platforms normalize to roughly **-14 LUFS**. Every clip we have shipped is 10–13 LU quiet, so
in-feed it plays noticeably softer than everything around it. `renderVertical` passes `-c:a aac` and
applies no audio filter whatsoever.

Try `loudnorm=I=-14:TP=-1.5:LRA=11`. Single-pass dynamic mode is adequate for speech; two-pass is more
accurate if the first pass is cheap enough to justify.

**Implementation catch worth knowing before you start:** the current invocation uses `-filter_complex`
with an unlabelled final output and no `-map`, relying on ffmpeg's automatic stream selection. Adding
`-af` alongside that is asking for a conflict. The clean form routes audio through the same graph
(`[0:a]loudnorm=...[a]`), labels the video output, and maps both explicitly — which is exactly why X5
below is the enabler.

**Judge:** `ffmpeg -i clip.mp4 -af ebur128 -f null -` before and after; target lands within ~1 LU of -14.
**Cost:** one filter plus the mapping change. **Risk:** low, but `loudnorm` will lift room tone in the
silences — listen for that.

## X4 — `ffprobe` → `MediaProfile`

There is no `ffprobe` call anywhere in the tree. `render.width/height` are static config and the
pipeline never once looks at the source it is editing.

Read width, height, fps, duration, audio channels. That single primitive unlocks:

- Detecting an already-vertical source instead of blindly letterboxing it.
- Choosing `zoom` from the real aspect ratio rather than a hardcoded constant.
- Warning when the upscale factor gets extreme, instead of shipping a soft clip silently.
- **Capping output fps.** Nothing sets `-r`, so `5a09516b` rendered every clip at **60fps** — verified
  on `clips/clip-1.mp4`. A screencast gains nothing from 60fps and it costs encode time and bitrate on
  the slowest stage. Capping to 30 is likely free speed.

**Judge:** run it over all three sources, print the profile, check against the table above.
**Cost:** ~40 lines through the existing `ProcessRunner`. **Risk:** none — it is read-only.

## X5 — `RenderPlan` and a labelled filtergraph builder

The enabler for X3, X6 and X7, and the honest bottleneck. `renderVertical(source, start, duration,
subtitles, target)` is five positional args feeding one format string with twelve `%d`/`%s`
substitutions and a single `-i`. Nothing the analysis learned can reach it.

Scope this deliberately small: **one `RenderPlan` record** carrying the inputs, geometry and options,
and **one builder** that assembles the graph from named labels with an explicit input list and
`-map`. Not the six-layer `MediaOperation` / `FilterGraphCompiler` chain — every clip currently gets an
identical graph, so a per-effect interface is speculative until there is real per-clip variation.
`ProcessRunner` already is the executor layer.

**Judge:** this is a refactor, so the test is *no visible change*. Generate the graph for the default
plan and assert it matches today's string, then re-render one clip and compare against the existing
mp4. If the output moves, the refactor is wrong.

**Sequencing constraint:** `docs/REVIEW.md` D3 is an unescaped subtitle path inside this exact
filtergraph. Fix the escaping **before** the graph becomes generated, not after — a hardcoded string
with one bad path is a latent bug; a builder with one bad path is a systemic one.

## X6 — Branding in the top dead zone

Depends on X5 (needs a second `-i`). 656px of untouched frame above the video.

Candidates: handle/logo at the top, a title card drawn from `content.json` (which already holds
generated per-platform titles), a lower third, a progress bar via `drawbox` with a time-varying width
expression.

**Judge:** purely visual, and specifically judge it against the *empty* version — dead space is not
automatically worse than clutter. **Cost:** moderate, and all of it after X5. **Risk:** this is the
one most likely to look amateur if overdone.

## X7 — Punch-in on scene cuts

`scenes.json` is computed every run, handed to `BoundaryRefiner` to pick a start time, then discarded.
A slow `zoompan` push on the foreground layer, keyed to detected cuts, would use a signal already paid
for.

**Honest caveat:** on a screencast, zooming crops code — the same objection that killed centre-cropping.
This may be actively bad. Test it precisely because it is cheap to judge and cheap to abandon.

## X8 — Scoring calibration

Not a render experiment, but it gates every render experiment: `ed94f058` produced **0 clips from 34
candidates**, and its `clips/` directory is empty on disk. Ranked scores on the run that survived were
`[61, 53, 52, 50, 50]` against `min-score: 50`.

Sweep `min-score` at 50 / 45 / 40 over the existing `candidates.json` files and look at how many
clips each threshold admits and whether the marginal ones are worth watching. Pair with the prompt's
"most candidates are average (3-5)" line, which is what puts the whole distribution on top of the cut.

**Judge:** re-score `ed94f058` and watch whatever clears 40. If the extra clips are junk, the threshold
is right and the scorer needs the loud failure from REVIEW A1 instead.

---

## X9 — The full effect stack

Everything below is implemented in `scripts/render-variant.py` and verified to parse and render on this
ffmpeg build. Each is an independent toggle, so they can be judged one at a time and combined once the
individual calls are made.

### Caption layer (ASS override tags — no filtergraph cost)

| Toggle | Tag | Effect |
|---|---|---|
| `caption_anim=fade` | `\fad(90,90)` | Captions fade rather than snap |
| `caption_anim=popin` | `\t(0,110,\fscx112)\t(110,200,\fscx100)` | Scale-up-and-settle on entry |
| `caption_anim=rise` | `\move(x,y+46,x,y,0,160)` | Slides up into place |
| `caption_glow` | `\blur4` + accent outline | Soft accent halo behind the text |
| `caption_style=karaoke` | `\kf` | Progressive left-to-right fill |
| `caption_style=pop` | per-word `\c` + `\fscx112` | Active word pops in accent |
| `subtitle_font` | — | `Arial Black` and `Impact` are installed; brand's Bricolage Grotesque is **not** |

These are free. libass does the work inside the `subtitles` filter that is already in the graph, so
animation costs no extra pass and no extra encode time.

### Panel layer — making the video look designed rather than letterboxed

| Toggle | Filter | Why |
|---|---|---|
| `sharpen` | `unsharp=5:5:0.7` | The foreground is downscaled 1920→1080; a light sharpen recovers code legibility |
| `panel_border` | `pad` in accent | An accent frame reads as intentional composition |
| `panel_shadow` | `drawbox` + `gblur` behind | Lifts the panel off the blurred fill |
| `darken_background` | `eq=brightness=-0.16:saturation=0.55` | Pushes the blurred fill back so the panel dominates |
| `vignette` | `vignette=PI/5` | Draws the eye to centre |
| `push_in` | `zoompan` to 1.045 over the clip | Slow drift; the one with real risk of cropping code |

### Furniture layer — filling the 1312px of dead space

| Toggle | Filter | Why |
|---|---|---|
| `title_card` | `drawtext` from `content.json` | The top 656px currently carries nothing, and the pipeline **already generates a per-clip title** — it is sitting in `content.json` unused by the renderer |
| `progress_bar` | `drawbox` with `w='iw*t/DUR'` | Proven retention device; accent-coloured, bottom edge |
| `handle` | `drawtext '@your_javaguy'` | Brand attribution |
| `intro_fade` / `outro_fade` | `fade=t=in/out` | Stops the hard cut in and out |

`title_card` is the one to look at hardest. It costs nothing to produce — the text already exists — and
it converts the single largest patch of wasted frame into the thing that makes someone stop scrolling.

### Two presets

- **`designed`** — everything above except `push_in`, tuned to be tasteful: Arial Black caps, 4-word
  pop captions with popin, accent panel, shadow, vignette, title card, progress bar, handle, loudnorm,
  30fps.
- **`maximal`** — `designed` plus `push_in`, bigger captions, `rise` animation, fatter progress bar.
  Deliberately overcooked, so there is an upper bound to react against.

Judge `designed` against `control` first. `maximal` exists to find the ceiling, not to ship.

### Results, clip 1

| Variant | MB | fps | Integrated |
|---|---|---|---|
| `control` | 1.03 | 60 | -27.3 LUFS |
| `glow` | 1.02 | 60 | -27.3 LUFS |
| `panel` | 1.03 | 60 | -27.3 LUFS |
| `progress` | 1.03 | 60 | -27.3 LUFS |
| `title` | 1.07 | 60 | -27.3 LUFS |
| `anim-popin` | 1.38 | 60 | -27.3 LUFS |
| `designed` | 1.75 | 30 | -14.4 LUFS |
| `maximal` | 1.78 | 30 | -14.4 LUFS |

Individual effects are nearly free — `glow`, `panel` and `progress` all land within 0.01 MB of control.
The cost is in **caption animation**: `anim-popin` alone adds 35%, because moving, scaling text defeats
inter-frame prediction. `designed` is 1.75 MB against 1.03 for a 26.5s clip — a 70% increase that is
still trivially small in absolute terms, and it is at 30fps, so the encode is no slower.

### Two defects found by looking at the frames

Both were mine, both are fixed in the harness, and both are worth knowing before this is ported to Java:

1. **The accent border was clipped left and right.** `pad=iw+2b:ih+2b` widened the panel past the frame,
   so centring pushed the side borders off-canvas — only top and bottom showed. The fix is to scale the
   foreground *down* by `2 × border` first, then pad back up to exactly `width`.
2. **The accent glow made the highlighted word illegible.** An accent-coloured active word inside an
   accent-coloured outline with `\blur4` is mush. Outline is now always black for legibility, with the
   accent moved to a `\shad4\4c` drop shadow and blur reduced to 2.

Neither would have been caught by the exit code — both renders "succeeded". Look at frames, not
return values.

### Source note

The test video is a **light-background slide deck**, not a dark code screencast. White captions over a
blurred *white* fill is the control's weakest point — legibility is poor above the gradient band. This
is most of why `darken_background` reads as such a large improvement, and it means the effect settings
here are tuned for light sources and may need re-tuning for a dark-themed recording.

---

## Blocked, not scheduled

**ROI crop instead of centre crop.** Needs a region of interest to track, and a code screencast has no
face and no speaker to follow. Real options are OCR-driven text density (already sketched as M3) or a
motion/edge-density heat map. That is research, not an afternoon — do not sequence it with the rest.

**Chroma key, transitions, colour grading.** Chroma key has no green screen to key. Transitions have
nothing to transition between — every clip is one contiguous window from one file. Colour grading a
code screencast mostly hurts legibility. None of these are gaps; they are capabilities this source
material does not call for.

---

## Test protocol

**None of X1, X2, X3, X6 or X7 requires running the app.** Every input is already on disk —
`source.mp4`, `transcript.json`, `scored.json`, and the per-clip `.ass` files. Prototype each variant
as a direct ffmpeg invocation against `storage/5a09516b-…/source.mp4`, render into a scratch directory,
and only port the winner into Java. That is a seconds-long feedback loop instead of a pipeline run, and
it keeps the app in your hands.

**Harness: `scripts/render-variant.py`** (stdlib only, same shape as the existing
`scripts/check-transcript.py`).

```bash
scripts/render-variant.py --list
scripts/render-variant.py control pop loudnorm
scripts/render-variant.py --clip 1,4 karaoke combo
scripts/render-variant.py --set subtitle_size=96 --set words_per_caption=4 control
```

It reads `clips.json` for the already-refined boundaries and `transcript.json` for word timings, writes
`storage/_scratch/<variant>/clip-N.{ass,mp4}` (inside the gitignored `/storage/`), and prints a
size / fps / duration / MB / LUFS table so audio and encode changes self-verify.

**Fidelity check — the control is exact.** `scripts/render-variant.py control` on `5a09516b` clip 1
produces an `.ass` that is byte-identical to the pipeline's and an mp4 of **1,029,793 bytes — the same
byte count as `storage/5a09516b…/clips/clip-1.mp4`**. If that ever stops matching, the port has drifted
and every comparison made with it is void. (The one deliberate divergence is the D8 timestamp clamp,
which affects clips 4 and 5 only.)

Fixed test clip: **`5a09516b` clip 1** (26.5s, 60fps source, already rendered, already measured at
-27.3 LUFS) as the control for every visual and audio comparison.

### First results, clip 1

| Variant | MB | fps | Integrated | Note |
|---|---|---|---|---|
| `control` | 1.02 | 60 | -27.3 LUFS | matches the pipeline byte for byte |
| `karaoke` | 1.17 | 60 | -27.3 LUFS | X1a renders; needs `SecondaryColour`, which the harness adds |
| `pop` | 1.21 | 60 | -27.3 LUFS | X1b renders; `.ass` grows 1,779 → 8,212 bytes |
| `loudnorm` | 1.04 | 60 | **-14.4 LUFS** | **X3 confirmed** — lands 0.4 LU off a -14 target |
| `fps30` | 0.90 | 30 | -27.3 LUFS | ~12% smaller; judge motion before adopting |

X3 is settled on the numbers — it does exactly what it should for one filter and costs 2% file size.
X1a/X1b both render cleanly, so the remaining question there is purely taste: watch them.

Judge on a phone. These are vertical clips and a 27-inch monitor flatters them.

## Suggested order

1. **X2** — free, and calibrates your eye before anything else changes.
2. **X3** — measured problem, small fix, invisible win.
3. **X1** — the big visible one; do it once X2 has settled caption geometry.
4. **X4** — cheap, unblocks conditional planning, probably free encode speed via the fps cap.
5. **X5** — only once X1/X3 have proven what the graph actually needs to carry. Fix REVIEW D3 first.
6. **X6, X7** — after X5, and both are genuinely optional.
7. **X8** — independent of all of the above; run it whenever the scorer next gets touched.

REVIEW Tier 1 (A1, A2) should land before X8, or a zero-clip run will keep reading as a taste problem
rather than a bug.
