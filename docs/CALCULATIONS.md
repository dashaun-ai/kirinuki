# Kirinuki — Calculations & Math Reference

Every number the pipeline computes, why it exists, and an honest verdict on whether it earns its
complexity. Guiding principle: this box is **CPU-only** and the LLM scoring call is the bottleneck
(~1 min/candidate), so the maths exists to do two things — **produce explainable, tunable rankings**
and **spend as few scoring calls as possible**. Anything beyond that is suspect.

Ordered by pipeline stage: candidate generation → scoring → rendering.

---

## 1. Candidate generation (`CandidateGenerator`)

Turns a flat word list into a handful of time windows worth scoring.

### 1.1 Sentence splitting
No maths — a word ends a sentence if it ends in `.`, `?`, or `!`. Sentences are the atomic unit so a
clip never starts or ends mid-word.

### 1.2 Windowing into groups
Config: `min = 20s`, `max = 60s` (`candidates.min/maxDuration`).

Walking the sentence list from `start`:
```
shortEnd = first sentence where  sentence.end − start.start ≥ min      (first window ≥ 20s)
longEnd  = last  sentence where  sentence.end − start.start ≤ max      (largest window ≤ 60s)
next group starts at longEnd + 1                                       (groups never overlap)
```
Each group emits a **short** window (just past 20s) and a **long** window (as much as fits under 60s).
Two lengths so the ranker can later prefer the tighter or the more complete framing of the same moment.

**Why necessary:** short-form platforms want 20–60s. The min/max are the hard product constraint; the
walk is the simplest way to honour it on sentence boundaries.

### 1.3 Thinning to a budget (`thinEvenly`)
Config: `maxCandidates = 40`.
```
groupLimit = max(1, maxCandidates / 2)          = 20      (÷2 because each group emits up to 2 windows)
step       = groups.size() / groupLimit
keep groups[ floor(index · step) ]  for index = 0 … groupLimit−1
```
Evenly samples groups across the video timeline so coverage is spread, not clustered at the start.

**Why necessary:** this is pure **cost control**. 40 candidates ≈ 40 scoring calls ≈ 40 minutes on this
box. Without the cap, a long video would be hours of inference. The `÷2` keeps the *scored* count at the
budget, not double it.

---

## 2. Scoring (`CandidateScorer`, `CandidateScore`)

The model returns four sub-scores; we compute the ranking. The model **never** returns an overall score —
that is ours, so ranking stays explainable and tunable.

### 2.1 Clamp sub-scores
```
clamp(v) = max(0, min(10, v))          each of hook / educationalValue / emotion / virality
```
**Why necessary:** the LLM occasionally returns 11 or −1. Clamping keeps every dimension in the promised
0–10 range so the weighted sum can't blow past 100.

### 2.2 Weighted overall score
Config weights: `hook 3, educationalValue 2, emotion 1, virality 3` → `total = 9`.
```
weighted     = 3·hook + 2·edu + 1·emo + 3·vir                   (max = 10 · total = 90)
overallScore = round( 100 · weighted / (10 · total) )           → 0 … 100
```
The `10` is the max sub-score. So `overallScore` is **the percentage of the best achievable weighted
score**. The normalisation is what lets you retune weights *without* rescaling `minScore` or breaking the
0–100 axis — which is exactly what happened when visual interest was dropped: the denominator fell from
100 to 90, so the same sub-scores now yield a score ~11% higher and `minScore: 50` admits more clips than
it used to.

**Why necessary:** hook and virality matter 3× more than emotion for short-form. Encoding that as tunable
weights (not baked into the prompt) means you change ranking priorities in config, and the number stays a
clean 0–100. Minimal and correct.

### 2.3 Overlap de-duplication
Const: `MAX_OVERLAP = 0.5`.
```
shared  = min(a.end, b.end) − max(a.start, b.start)      (seconds of intersection)
shorter = min(a.duration, b.duration)
a and b are "the same moment"  ⇔  shared / shorter > 0.5
```
Note it is **intersection-over-shorter**, not IoU. That is deliberate: the short+long windows from §1.2
are *nested* — the short one sits fully inside the long one, so `shared` = the short window's whole
length and the ratio is ~1.0. Plain IoU (`shared / union`) could fall under 0.5 and fail to catch the
nested pair. Intersection-over-shorter catches nesting correctly.

**Why necessary:** without it you'd ship two near-identical clips of the same 30 seconds. It's the cleanup
that makes the two-window strategy safe.

### 2.4 Ranking
Config: `minScore = 0`, `topClips = 8`.
```
drop anything with overallScore < minScore
sort by overallScore descending
walk the list, keep a candidate only if it doesn't overlap (§2.3) one already kept
stop at topClips
```
Greedy highest-score-first with overlap rejection — the standard way to get the top-N *distinct* moments.

---

## 3. Rendering (`ClipRenderer`, `FfmpegClient`, `SubtitleWriter`)

### 3.1 Boundary padding
Config: `lead-in = 400ms`, `tail = 600ms`. The raw candidate boundary lands on a whisper word edge, which
snaps hard. Padding gives the cut room to breathe:
```
start = candidate.start − leadIn
end   = candidate.end   + tail
```
Asymmetric because endings feel more abrupt than starts. `leadIn > gapBefore` would pull a neighbouring
sentence's word into the clip (audio + caption bleed), so each pad is **clamped to the real gap**:
```
leadIn = min(400ms, gapBefore)     gapBefore = firstWord.start − previousWord.end
tail   = min(600ms, gapAfter)      gapAfter  = nextWord.start  − lastWord.end
```
This uses whisper's own word timings — the same clock the subtitles use — so captions never bleed.
**Necessary and minimal.**

### 3.2 Silence snapping (PROTOTYPE — see §4)
Config: `silence-threshold = -30dB`, `min-silence = 100ms`, `snap-window = 1s`.
One `silencedetect` pass over `audio.wav` yields silence intervals `[s, e]`. For each boundary, pick the
interval whose edge is nearest the whisper boundary (within the window) and snap into it:
```
start = max(silence.start, silence.end − leadIn)     (begin inside the pre-speech silence)
end   = min(silence.end,   silence.start + tail)     (end inside the post-speech silence)
```
Falls back to §3.1 when no silence sits within the window. **Verdict in §4 — this is the one that doesn't
pay off.**

### 3.3 Vertical frame geometry (`verticalFilter`)
Config: `width = 1080`, `height = 1920`, `zoom = 1.0`, `crf = 23`.
```
blurWidth  = max(2, width / 8)  = 135     downscale the background before blurring — boxblur on 135×240
blurHeight = max(2, height / 8) = 240     is far cheaper than on 1080×1920, then scale back up
zoomed     = round(width · zoom / 2) · 2  = 1080     foreground width, forced EVEN (H.264 requires it)
overlay at ((W−w)/2, (H−h)/2)             centre the foreground over the blurred fill
```
**Why necessary:** the source is a 720p 16:9 screen-share; a 9:16 crop would slice code in half, so the
frame is a blurred fill with the sharp video centred. The `/8` blur downscale is a real CPU saving; the
`round(·/2)·2` even-rounding is a hard H.264 encoder requirement, not a nicety.

### 3.4 Subtitle timing (`SubtitleWriter`)
Config: `wordsPerCaption = 5`.
```
words in clip:   word.end > clipStart  AND  word.start < clipEnd
caption time  =  wordTime − clipStart                    (re-base to clip-relative 0)
  start = max(0, first.start − clipStart)
  end   = min(clipEnd − clipStart, last.end − clipStart)  (clamp so a padded caption can't run past the clip)
group every 5 words into one caption line
timestamp H:MM:SS.cc:  total = ⌊sec⌋,  centis = round((sec − total) · 100)
```
**Why necessary:** ASS subtitles are clip-relative, so every word time must be re-based and the format is
centisecond-precision. All of it is required by the format; nothing extra.

---

## 4. Over-engineering audit

| Calculation | Verdict |
|---|---|
| Sentence windowing (§1.2) | **Necessary** — the 20–60s product constraint, minimally expressed. |
| `thinEvenly` budget cap (§1.3) | **Necessary** — the only thing standing between us and hours of inference. |
| Clamp (§2.1) | **Necessary** — one-liner guarding against a misbehaving model. |
| Weighted 0–100 score (§2.2) | **Necessary & well-judged** — tunable priorities on a stable scale; not baked into the prompt. |
| Overlap dedup (§2.3) | **Necessary, but coupled** — exists mainly to clean up the two-window strategy below. |
| Greedy top-N ranking (§2.4) | **Necessary** — standard, minimal. |
| Padding + gap-clamp (§3.1) | **Necessary & minimal** — clean soft-ish cuts, no bleed. |
| Frame geometry (§3.3) | **Necessary** — forced by the 720p source and the H.264 even-dimension rule. |
| Subtitle timing (§3.4) | **Necessary** — all required by the ASS format. |
| **Short + long dual windows (§1.2)** | **⚠️ Questionable** — doubles the *most expensive* operation (scoring) to try two framings of one moment, and §2.3 then throws one away. On a CPU box that's real minutes for a marginal ranking gain. Worth measuring: does the long window ever actually win over its short twin? If rarely, generate one window per group and the dedup mostly disappears too. |
| **Silence snapping (§3.2)** | **⚠️ Doesn't earn its keep** — proven on real clips: it only helps the 2 of 5 boundaries that sit at a genuine pause; where the speaker talks through the cut there's no silence to snap to, and at −30 dB it snapped to −27 dB soft-speech and reintroduced caption bleed. The right layer for "cut at a pause" is candidate *selection*, not render-time nudging. Recommend revert. |
| **`media.frameInterval` (config)** | **☠️ Dead** — declared, defaulted to `10s`, referenced nowhere since thumbnail generation was dropped. Delete it. |

### Bottom line
The core maths is **not over-engineered** — almost every calculation maps directly to a product
constraint (clip length, 0–100 ranking, 9:16 framing, ASS format) or the CPU-cost constraint. Two things
are worth trimming: **revert the silence-snapping prototype** (wrong layer, we have the evidence) and
**delete the dead `frameInterval`**. One thing is worth a measurement before the next phase: whether the
**short+long dual-window** generation justifies doubling the scoring cost.
