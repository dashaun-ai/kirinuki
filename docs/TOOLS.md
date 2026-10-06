# The tools Kirinuki drives

Kirinuki is a Spring Boot app, but it barely touches a pixel or an audio sample itself. It is an
**orchestrator**: it decides what to do and in what order, then shells out to four specialist tools
that do the heavy lifting. Understanding those tools is understanding most of the product.

This document explains each one: what it is, how it works under the hood, and exactly how we use it
here — with the real commands from the code. Each section ends with something to change and re-run, so
the knowledge sticks.

The four tools:

| Tool | Role | Lives where |
|---|---|---|
| **yt-dlp** | Download the source video from a URL | external binary on `PATH` |
| **ffmpeg** | Every pixel and sample transform: extract audio, cut, reframe, subtitle | external binary on `PATH` |
| **whisper** (`whisper-ctranslate2`) | Speech → text with per-word timestamps | external binary on `PATH` |
| **Ollama** + Spring AI | Run a local LLM to score clip candidates | HTTP service on `:11434` |

The first three are separate programs we run as subprocesses. Ollama is a long-running server we talk
to over HTTP. That difference matters and is discussed at the end.

---

## 1. yt-dlp — acquiring the video

### What it is

`yt-dlp` is a command-line program that downloads video from YouTube (and ~1800 other sites). It is a
maintained fork of the older `youtube-dl`. We treat it as an external, pinned binary — like ffmpeg —
not a Java library, because there is no reliable Java equivalent and the official YouTube Data API
**cannot download video content**, only read metadata.

### How it works internally

When you give YouTube a video URL, the page returns a **player response**: a big JSON blob listing
every available *format* of that video. YouTube stores each quality/codec as a **separate stream** —
1080p video with no audio here, 720p with no audio there, the audio track on its own somewhere else.
This is called **adaptive streaming**: your browser picks a video stream and an audio stream that suit
your bandwidth and screen, downloads both, and plays them together.

yt-dlp does four things:

1. **Extract** the player response (this is the fragile part — YouTube changes the page often, which is
   why we pin the version and update deliberately).
2. **Deobfuscate** the stream URLs. YouTube protects them with a JavaScript "signature" that must be
   run to unlock the real URL. This is why yt-dlp warns about needing a JavaScript runtime (`deno`) —
   without one, some higher-quality formats are unavailable.
3. **Select** which format(s) to download, based on a format string you pass.
4. **Download and merge** the chosen video and audio streams into a single file (using ffmpeg under the
   hood for the merge).

### How we use it

Two separate calls, both in `YtDlpClient`.

**Metadata first**, synchronously, inside the `POST /videos` request:

```
yt-dlp --dump-json --no-playlist <url>
```

`--dump-json` prints one big JSON object describing the video *without downloading it*. We read the
`id`, `title`, `duration` and `uploader` from it. Doing this first is deliberate: an unavailable,
private, or region-locked video fails here in ~3 seconds, so we return an error and never create a
database row for a video we can't process.

**Download second**, in the background pipeline:

```
yt-dlp --no-playlist --no-progress --force-overwrites \
       --merge-output-format mp4 \
       -f "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best" \
       -o <target> <url>
```

The interesting flag is `-f`, the **format selector**. Read it right-to-left as a fallback chain:

- `bestvideo[ext=mp4]+bestaudio[ext=m4a]` — best MP4 video stream **plus** best M4A audio stream,
  merged. The `+` is what triggers the merge.
- `/best[ext=mp4]` — if that combination isn't available, fall back to the best pre-merged MP4.
- `/best` — if even that fails, take the best of anything.

`--merge-output-format mp4` forces the merged container to be `.mp4`.

### The gotcha we hit

On real videos, YouTube often serves **AV1** video inside an MP4 container, because AV1 is more
efficient than H.264 at the same quality. Our selector says "mp4", and it got AV1. That's fine for
storage, but AV1 is ~2–3× slower to *decode* than H.264 — and every clip we render later has to decode
the source. On a CPU-only box that adds up. The proper fix is normalising the source to a known codec
at ingest (a deferred `FR-MEDIA-2` feature), not fighting the selector.

### Try this

```
yt-dlp -F <a-youtube-url>
```

`-F` lists every format YouTube offers for that video — a table of resolution, codec and size. You'll
see the adaptive streams split apart (video-only, audio-only) and understand why yt-dlp has to merge.
Notice which resolutions offer H.264 (`avc1`) versus AV1 (`av01`); above 1080p, H.264 usually vanishes.

---

## 2. ffmpeg — the media Swiss army knife

### What it is

`ffmpeg` is the program that does essentially all video/audio manipulation on the planet. YouTube,
Netflix, VLC, OBS — they all use it or its libraries. It reads media in almost any format, transforms
it, and writes it back out. If Kirinuki "cuts a clip" or "extracts audio", ffmpeg is what actually
does it. We never manipulate pixels in Java.

### The mental model: streams, codecs, containers, filters

Four concepts unlock ffmpeg. In Java terms:

- **Container** (`.mp4`, `.wav`, `.mkv`) — the *file format*, the box. Like a `.zip`: it holds things
  but isn't the things. It says "there's a video stream and an audio stream inside, here's how they're
  interleaved."
- **Stream** — one track inside the container: a video stream, an audio stream, a subtitle stream.
- **Codec** (H.264, AV1, AAC, PCM) — the *compression scheme* for a stream. **enCOder/DECoder**.
  "Decode" = uncompress to raw pixels/samples. "Encode" = compress raw data back down. This is the
  expensive CPU work.
- **Filter** — a transform applied to raw (decoded) frames: crop, scale, blur, overlay, burn
  subtitles.

Every ffmpeg command is the same pipeline:

```
read container → demux into streams → DECODE to raw frames → apply FILTERS → ENCODE → mux into container → write
```

When you see ffmpeg "taking a while", it's almost always the decode and encode steps — turning
compressed video into raw pixels, transforming them, and compressing back down.

### How we use it — three jobs

#### Job A: extract audio for transcription (`FfmpegClient.extractAudio`)

```
ffmpeg -y -i source.mp4 -vn -ac 1 -ar 16000 -c:a pcm_s16le audio.wav
```

Flag by flag:

- `-i source.mp4` — input.
- `-vn` — "**v**ideo **n**o": drop the video stream entirely. We only want audio, and decoding video we
  won't use is wasted work. This is why extracting audio from a 14-minute video takes ~15 seconds, not
  minutes — the expensive video decode never happens.
- `-ac 1` — "**a**udio **c**hannels 1": downmix stereo to mono. Speech carries no useful stereo
  information, and Whisper wants mono.
- `-ar 16000` — "**a**udio **r**ate 16000": resample to 16 kHz. Whisper's models are trained at exactly
  16 kHz; giving it anything else makes it resample internally, so we do it once here.
- `-c:a pcm_s16le` — "**c**odec for **a**udio = PCM signed 16-bit little-endian": *uncompressed* audio.
  Whisper spends zero cycles decoding it.

A counterintuitive result: the `audio.wav` (26 MB) is often **larger than the source video** (22 MB).
PCM is uncompressed, so its size is pure arithmetic: 16000 samples/sec × 2 bytes × 1 channel = 32 KB
per second. The video, by contrast, is heavily compressed. Uncompressed 14-minute speech beats
compressed 14-minute 720p.

#### Job B: render a vertical clip (`FfmpegClient.renderVertical`)

This is the most complex command in the app. Source video is 16:9 (landscape, 1280×720). Short-form
platforms want 9:16 (vertical, 1080×1920). You cannot just squeeze one into the other; you must decide
what to do with the shape mismatch.

We prototyped three approaches and **looked at the frames**:

- **Centre crop** — take a vertical slice from the middle. Fast, but on a screen-share it slices the
  code in half. Unusable. (We proved this by rendering a frame and reading it.)
- **Blur fill** — put the whole video in a horizontal band, fill the empty space above and below with a
  blurred, zoomed copy of the same video. Preserves everything — but on a 16:9 screen-share the source
  only fills a ~35% horizontal band, so the code ends up small and hard to read on a phone while ~60% of
  the frame is blurred dead space.

We use blur fill: it's the best *deterministic* option and never slices content, but the legibility
trade-off above is real. The proper fix is **content-aware reframing** — detect the region of interest
(active code / speaker) and crop-and-zoom it to fill the frame — which needs the M3 visual/OCR
extractors, so it's tracked as a future render requirement (`FR-RENDER-6`), not attempted here.

The command:

```
ffmpeg -y -ss <start> -i source.mp4 -t <duration> \
  -filter_complex "<the filter graph>" \
  -c:v libx264 -preset veryfast -crf 23 -c:a aac -movflags +faststart clip.mp4
```

- `-ss <start> -t <duration>` — seek to the clip's start time and take `duration` seconds. This is how
  we "cut" — we don't copy the whole video, we decode only the window we need.
- `-c:v libx264` — encode the output video with H.264 (universally playable).
- `-preset veryfast` — libx264 trades encoding speed against file size; `veryfast` favours speed, which
  matters on CPU.
- `-crf 23` — "**c**onstant **r**ate **f**actor": quality knob, 0 (lossless, huge) to 51 (awful). 23 is
  the sensible default.
- `-c:a aac` — encode audio as AAC.
- `-movflags +faststart` — move the MP4's index to the front so it can start playing before fully
  downloaded. Standard for web video.

The `-filter_complex` is a **filter graph** — a little dataflow program. Ours, annotated:

```
[0:v]split=2[bg][fg];                                          # copy the video into two streams: bg and fg
[bg]scale=135:240:force_original_aspect_ratio=increase,        # bg: shrink tiny...
    crop=135:240,boxblur=8:2,scale=1080:1920[b];               #     ...blur cheaply, blow back up to full frame
[fg]scale=1080:-2,crop=1080:ih:(iw-1080)/2:0[f];               # fg: scale to full width, keep aspect
[b][f]overlay=(W-w)/2:(H-h)/2,                                 # lay fg centred over the blurred bg
      subtitles=clip.ass                                       # burn the subtitle file into the pixels
```

The `[name]` tags are named intermediate streams — think local variables in a dataflow graph. `split`
duplicates the input so we can process the background and foreground independently, then `overlay`
recombines them.

**The optimisation worth knowing:** blurring is expensive, and its cost scales with resolution.
Blurring a full 1080×1920 frame took 131 seconds. Blurring a *tiny* 135×240 copy and then scaling it
up to 1920 looks identical (it's blurred — detail is gone anyway) and took 37 seconds. That's the
`scale down → blur → scale up` dance in the `[bg]` chain. A 3.5× speedup for free.

**A gotcha that cost us a debugging round:** ffmpeg does not auto-map a filter output that has a
**label**. `...subtitles=clip.ass[out]` fails with "Invalid argument". Leaving the last filter
unlabelled (`...subtitles=clip.ass`) lets ffmpeg map it automatically, and lets the audio through too.

#### Job C: the thumbnail (`FfmpegClient.thumbnail`)

```
ffmpeg -y -ss <halfway> -i clip.mp4 -frames:v 1 thumbnail.jpg
```

Seek to the middle of the finished clip, grab exactly one frame (`-frames:v 1`), write it as a JPEG.
Trivial compared to the render.

### Subtitles: the `.ass` format

`FR-RENDER-2` says subtitles must come from the word-level transcript. We generate an **ASS** file
(Advanced SubStation Alpha — a subtitle format that supports positioning, fonts, colours, outlines).
`SubtitleWriter` filters the transcript's words to the clip's time window, rebases their timestamps to
be clip-relative (a word at 402s in a clip starting at 400s becomes 2s), groups them ~5 words per line,
and writes ASS "Dialogue" events. ffmpeg's `subtitles=` filter then *burns* them into the pixels — they
become part of the image, not a toggleable track, which is what short-form platforms need.

### Try this

Take any clip and re-render it with a different crop strategy to *see* the difference:

```
# centre crop — watch it destroy a screen-share
ffmpeg -y -ss 60 -i storage/<id>/source.mp4 -t 20 \
  -vf "crop=405:720:437:0,scale=1080:1920" out-crop.mp4
```

Open `out-crop.mp4` next to a rendered clip. That single comparison teaches you why the blur fill
exists. Then try changing `-crf 23` to `-crf 35` and see the quality collapse.

---

## 3. Whisper — speech to text with timestamps

### What it is

Whisper is an open speech-recognition model from OpenAI (the weights are freely downloadable and run
locally — nothing is sent anywhere). It takes audio and produces text. Crucially for us, it also emits
**word-level timestamps**: not just "here's the transcript" but "the word *RabbitMQ* was spoken from
4.20s to 4.85s". Everything downstream depends on those timings — candidate boundaries, subtitle
alignment, clip cuts.

We use **`whisper-ctranslate2`**, a faster reimplementation. Same models, but the inference runs on the
CTranslate2 engine, which is roughly 4× faster on CPU than the original Python Whisper. Since this stage
runs on every quality-gate iteration, that speedup matters.

### How it works internally (the useful mental model)

Whisper is a **transformer**, the same architecture family as an LLM, but trained on
(audio → text) pairs instead of (text → text). Simplified pipeline:

1. Audio is chopped into 30-second windows.
2. Each window becomes a **mel spectrogram** — a picture of which frequencies are loud at each moment.
   (This is why 16 kHz mono matters: the model was trained on exactly that representation.)
3. An **encoder** turns the spectrogram into a numeric summary.
4. A **decoder** generates the transcript one token at a time, like an LLM predicting the next word,
   but conditioned on the audio.
5. Word timestamps come from **alignment** — cross-referencing which audio frames the model was
   "attending to" when it emitted each word.

Model size is a quality/speed trade. `base.en` (English-only, small) is fast and fine for clear
speech — what we use on the CPU box. `large-v3` is much more accurate, much slower, and wants a GPU —
the `gpu` profile.

### How we use it (`WhisperClient`)

```
whisper-ctranslate2 audio.wav \
  --model base.en --device cpu --compute_type int8 \
  --word_timestamps True --output_format json --output_dir <dir>
```

- `--model base.en` — which model weights (downloaded once on first run, then cached).
- `--device cpu` — run on CPU. The `gpu` profile sets `cuda`.
- `--compute_type int8` — **quantisation**. The model's numbers are squashed from 16/32-bit floats down
  to 8-bit integers. Smaller and faster, with a small accuracy cost. `float16` on GPU keeps more
  precision.
- `--word_timestamps True` — the whole reason we chose Whisper. Without it you get sentence-level
  timing, which isn't precise enough to snap a clip to a word boundary.
- `--output_format json` — structured output we can parse.

**One wrinkle in the code:** the CLI names its output after the *input* file, so `audio.wav` produces
`audio.json`. Our pipeline wants `transcript.json`. So `WhisperClient` runs the tool, then renames
`audio.json` → `transcript.json`. Small, but it's why that rename exists.

The resulting JSON has `segments`, each with a `words` array of `{word, start, end}`. That word list is
the atomic unit everything downstream is built from.

### Why it's slow, and why that's the whole story

On CPU, transcribing a 14-minute video takes ~4 minutes with `base.en`. That's actually the *fast*
part of the pipeline. The slow part is scoring (next section), and the reason both are slow is the
same: transformer inference on a CPU is fundamentally expensive. This is the single biggest argument
for the client's GPU box.

### Try this

Run it by hand on an existing `audio.wav` and inspect the output:

```
whisper-ctranslate2 storage/<id>/audio.wav --model base.en --word_timestamps True --output_format json --output_dir /tmp
python3 -c "import json; d=json.load(open('/tmp/audio.json')); print(d['segments'][0]['words'][:5])"
```

You'll see the `{word, start, end}` structure. Then try `--model tiny.en` and compare speed and
accuracy. That trade — bigger = better + slower — is the same trade you'll make with the LLM and on the
GPU box.

---

## 4. The chat model + Spring AI

### What it is

The app reaches its chat model over an **OpenAI-compatible HTTP endpoint**, configured by
`OPENAI_BASE_URL` / `OPENAI_API_KEY` (`spring-ai-starter-model-openai`). That is a *transport*, not a
hosting decision: **Ollama serves an OpenAI-compatible API at `:11434/v1`**, so pointing at a local
Ollama satisfies ADR-0001 exactly as before. Point it at a gateway instead and video/transcript data
leaves the box — which ADR-0001 does not permit.

**Ollama** is a program that runs large language models locally. Think of it as "Docker for LLMs": you
`ollama pull gemma3` and it downloads the model; it then serves an HTTP API on `localhost:11434` that
you send prompts to and get completions back. Nothing leaves your machine, and there's no per-call
cost — the two properties `ADR-0001` is built on.

**Spring AI** is the Java library that talks to any of these through one common interface,
`ChatClient`. We deliberately code against `ChatClient`, never against provider-specific option classes,
so switching models or providers is a config change, not a code change. That discipline is what made
the move off the Ollama-specific starter a config-only change.

Unlike the other three tools, Ollama is **not a subprocess** — it's a long-running server. We don't
start a new process per request; we send an HTTP call to a model that's already loaded in memory. That
distinction is the subject of the undrafted ADR-0005 (see below).

### How an LLM call works here

For each clip candidate, `CandidateScoreClient` sends the candidate's transcript text to the model and
asks it to rate the clip. The magic is **structured output**: instead of parsing free-form text, we
call `.entity(CandidateScore.class)` and Spring AI:

1. Appends instructions to the prompt telling the model to reply as JSON matching that shape.
2. Receives the reply.
3. Parses it into a `CandidateScore` Java record.
4. If parsing fails, throws — and our `@Retryable` retries the call.

So the model returns `{hook: 7, educationalValue: 8, ...}` and we get a typed Java object. That's the
whole `FR-SCORE-2` "schema-validated structured output" requirement.

### The knobs that bit us

Local LLM inference on CPU is slow, and a bounded reply is the difference between seconds and minutes:

- **`spring.ai.openai.chat.max-tokens: 512`** — the maximum tokens the model may generate (the
  OpenAI-compatible spelling of Ollama's `num-predict`). Unset, it's *unbounded*: a model that starts
  rambling generates until it fills its context window, which on CPU is minutes per call. A
  `CandidateScore` is ~100 tokens. **This one cost hours to diagnose** — the pipeline appeared to hang.
- **`temperature: 0.0`** (`scoring.temperature`) — randomness. Zero means near-deterministic:
  re-running gives the same scores. Essential for a quality gate, where you need to compare runs and
  know a difference is real, not noise.

> **Lost in the move to the OpenAI-compatible starter:** `num-thread`, `num-ctx` and the other
> Ollama-native options are not part of the OpenAI chat API, so they can no longer be set per request
> from `application-*.yml`. If you run against a local Ollama on this CPU box, set them on the server
> instead (`OLLAMA_NUM_THREAD`, or a Modelfile `PARAMETER`). Keeping threads at 6 of 12 is what leaves
> the IDE and Docker usable.

### The prompt engineering that matters

Two techniques make a small 4B model reliable enough to trust:

- **Few-shot anchoring** — the system prompt includes one *weak* example (filler → all low scores) and
  one *strong* example (a stakes-driven hook → high scores). A small model without examples scores
  everything as mediocre-to-good; with them, it discriminates. We proved this: filler text scored 2/1/1.
- **Deterministic aggregation** — the model returns *sub-scores* (hook, education, emotion, etc.) but
  **not** an overall score. The overall is a weighted sum computed in Java (`CandidateScorer`). This
  keeps the ranking explainable ("it ranked high because hook×3 + virality×3") and tunable via
  `scoring.weights` without re-prompting. It also honours the PRD principle: the model provides
  *judgement*, deterministic code does the *arithmetic*.

### Subprocess vs. service (ADR-0005, undrafted)

Whisper and ffmpeg are **cold**: a new process each time. That's fine — they load fast. An LLM is
different: loading `gemma3` into memory takes seconds and gigabytes. Ollama keeps it **warm** between
requests, which is why it's a server, not a subprocess. In production, ASR should arguably move to the
same model — a long-lived local service that keeps Whisper warm, rather than paying the load cost per
video. Still fully local; the axis is *cold vs warm*, not *local vs hosted*. That's the pending
ADR-0005.

### Try this

Talk to Ollama directly, bypassing the app, to see the raw structured-output mechanism:

```
curl localhost:11434/api/chat -d '{
  "model": "gemma3",
  "messages": [{"role":"user","content":"Rate this clip 0-10 for hook. Reply as JSON {\"hook\": N}. Text: Here is the mistake that cost us $50,000 overnight."}],
  "stream": false,
  "format": "json",
  "options": {"temperature": 0, "num_predict": 100}
}'
```

Watch it return JSON. Then remove `"num_predict": 100` and a verbose prompt, and watch a call take far
longer — that's the bug that hung our pipeline, reproduced in one command.

---

## The pattern across all four

Notice the shape that repeats:

1. **Kirinuki decides, the tool does.** Every expensive operation is delegated. The Java code is thin
   glue: build a command, run it, handle the result.
2. **`ProcessRunner` is the single door** to the three subprocess tools. It runs any binary with
   virtual-thread stream draining and a timeout, and turns a non-zero exit into a typed exception. yt-dlp,
   ffmpeg and Whisper all go through it.
3. **Configuration, not code, holds the tuning.** Model names, thread counts, CRF, ASR model size,
   timeouts — all in profiles, so the same build runs on a laptop and a 4090.
4. **The bottleneck is always transformer inference on CPU** (Whisper and the LLM). ffmpeg and yt-dlp
   are fast by comparison. This is the throughline of every "why is it slow" moment, and the reason the
   GPU box exists.

Read `MODULES.md` next: it walks the Java code that coordinates all of this.
