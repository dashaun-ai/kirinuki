# Glossary

Plain-language definitions of the terms used across `TOOLS.md` and `MODULES.md`. Written for a Java
developer meeting media, ASR, and LLM concepts for the first time. Where it helps, there's a Java or
Spring analogy.

Grouped by area. Skim the group you need; you don't have to read it top to bottom.

---

## Media & video

**Container** — the file *format*, the box that holds streams: `.mp4`, `.mkv`, `.wav`. Like a `.zip` or
a `.jar` — it holds things and describes how they're arranged, but isn't the things themselves. An
`.mp4` typically holds one video stream and one audio stream, interleaved.

**Stream** — one track inside a container: a video stream, an audio stream, a subtitle stream. A single
file can carry several.

**Codec** — the compression scheme for a stream. Short for **enCOder/DECoder**. Examples: H.264, AV1
(video); AAC, PCM (audio). The container is the box; the codec is *how the contents are compressed
inside it*. A `.mp4` can hold H.264 or AV1 video — same box, different compression.

**Encode / Decode** — *decode* = uncompress a stream back to raw pixels or audio samples. *Encode* =
compress raw data down again. These are the CPU-heavy steps; when ffmpeg "takes a while", it's almost
always decoding and re-encoding.

**Demux / Mux** — *demux* (demultiplex) = split a container into its separate streams on the way in.
*Mux* = interleave streams back into a container on the way out. The bookends of every ffmpeg run:
`demux → decode → filter → encode → mux`.

**H.264 (avc1)** — the most widely supported video codec. Plays everywhere. What we *encode clips to*.

**AV1 (av01)** — a newer, more efficient video codec (smaller files at the same quality), but ~2–3×
slower to decode. YouTube often serves it, which is why our source videos are sometimes AV1 and why
decoding them repeatedly costs us on CPU.

**AAC** — the standard compressed audio codec, used inside MP4. What we encode clip audio to.

**PCM** — *uncompressed* audio (raw samples). Big, but nothing has to decode it. We extract audio as PCM
(`pcm_s16le` = signed 16-bit, little-endian) because Whisper then spends zero cycles decompressing it.

**Sample rate** — how many audio samples per second, in Hz. `16000` (16 kHz) is what Whisper's models
are trained on. `44100` (44.1 kHz) is CD quality, common in source video. We downsample to 16 kHz.

**Channels** — mono (1) or stereo (2). Speech carries no useful stereo information, so we downmix to
mono for transcription.

**Filter / filter graph** — an ffmpeg transform applied to *decoded* frames: crop, scale, blur, overlay,
burn subtitles. A **filter graph** chains several together into a little dataflow program. The `[name]`
tags in a graph are named intermediate streams — think local variables.

**CRF (Constant Rate Factor)** — libx264's quality knob, 0 (lossless, huge file) to 51 (tiny, ugly).
Lower = better and bigger. 23 is the sensible default; we use it.

**Preset** — libx264's speed-vs-size trade (`ultrafast` … `veryslow`). Faster presets encode quicker but
produce slightly larger files at the same quality. We use `veryfast` because CPU time is the constraint.

**Resolution / aspect ratio** — pixel dimensions (`1280×720`) and their shape ratio. 16:9 is landscape
(normal video), 9:16 is vertical (short-form). The mismatch between them is the whole reframing problem.

**Reframe** — converting one aspect ratio to another. Our clips reframe 16:9 source into 9:16 vertical,
using a blurred-background fill so nothing is cropped away.

**ASS (Advanced SubStation Alpha)** — a subtitle file format that supports positioning, fonts, colours,
and outlines. We generate one per clip and ffmpeg *burns* it into the pixels.

**Burn (subtitles)** — render subtitles permanently into the video frames, so they're part of the image
rather than a separate toggleable track. Short-form platforms need burned subtitles.

---

## Speech recognition (ASR)

**ASR (Automatic Speech Recognition)** — turning spoken audio into text. Whisper is our ASR engine.

**Whisper** — an open speech-recognition model from OpenAI. Runs locally (weights are downloadable);
nothing is uploaded. Produces a transcript *and* per-word timestamps.

**Word-level timestamps** — the start and end time of each individual word (`RabbitMQ: 4.20s–4.85s`).
The reason we chose Whisper — everything downstream (clip boundaries, subtitle timing) needs them.

**Model (in ASR)** — the trained weights. `base.en` is small, fast, English-only; `large-v3` is bigger,
slower, far more accurate, and wants a GPU. A quality-vs-speed dial.

**Mel spectrogram** — a picture of which audio frequencies are loud at each moment, weighted to match
human hearing. Whisper doesn't "hear" audio; it reads this image. Trained at 16 kHz, which is why the
sample rate matters.

**Quantisation** — squashing a model's numbers from high-precision floats (16/32-bit) down to low
precision (`int8`). Smaller, faster, slightly less accurate. `--compute_type int8` on CPU;
`float16` on GPU keeps more precision.

**CTranslate2** — the fast inference engine `whisper-ctranslate2` runs on. Same Whisper models, ~4×
faster on CPU than the original Python implementation.

---

## Large language models (LLMs)

**LLM (Large Language Model)** — a model trained to predict the next token of text, which turns out to be
enough to summarise, classify, reason, and — for us — judge a clip. We use one to *score* candidates.

**Token** — the unit an LLM reads and writes. Roughly ¾ of a word (common words are one token, rare
ones split into several). Models are measured and billed in tokens. Our scoring prompt is ~2,600 input
tokens, mostly the few-shot examples and schema; the reply is ~100.

**Context window** — the maximum number of tokens (prompt + reply) a model can consider at once, set by
`num-ctx`. Overflow it and the model loses the start. We cap it at 4096.

**Temperature** — randomness in generation, 0 to ~2. `0` is near-deterministic (same input → same
output), which we need so a quality-gate re-run is comparable, not noisy. Higher = more creative and
variable.

**num-predict** — the cap on how many tokens the model may *generate*. Unset, generation is unbounded —
a rambling reply runs to the context limit, which on CPU is *minutes*. Setting it to 300 turns a
runaway into a fast failure. (This unset value hung our pipeline for hours before we found it.)

**Structured output** — forcing the model to reply as JSON matching a defined shape, so you get a typed
object instead of parsing prose. Spring AI's `.entity(CandidateScore.class)` does this: it instructs
the model, then parses the reply into a Java record, retrying if it's malformed.

**Few-shot (prompting)** — putting a couple of worked examples in the prompt (here: one weak clip → low
scores, one strong clip → high scores) so the model calibrates. A small model without examples rates
everything as mediocre; with them, it discriminates. (Contrast *zero-shot*: instructions only, no
examples.)

**Inference** — running a trained model to get an output (as opposed to *training* it). All our LLM and
Whisper work is inference. Transformer inference on CPU is the pipeline's bottleneck.

**Transformer** — the neural-network architecture behind both LLMs and Whisper. You don't need the
internals; the useful fact is that inference is expensive, and a GPU does it many times faster than a
CPU.

**Quantisation (in LLMs)** — same idea as in ASR: shrinking the model's numbers to run smaller and
faster. `gemma3` at "Q4" is a 4-bit-quantised model — why a "4B" (4-billion-parameter) model fits in a
few GB.

**Weights / parameters** — the learned numbers that *are* the model. "4B" = 4 billion parameters. More
parameters generally means smarter and slower.

---

## Infrastructure & the tools

**Ollama** — a program that runs LLMs locally and serves them over HTTP on `:11434`. "Docker for LLMs":
`ollama pull gemma3` downloads a model, then you send it prompts. Keeps the model *warm* (loaded in
memory) between requests. It also exposes an **OpenAI-compatible** API at `:11434/v1`, which is how
this app reaches it.

**OpenAI-compatible endpoint** — an HTTP API that speaks the same request/response shape as OpenAI's
chat completions, whoever is actually serving it. Ollama, vLLM, llama.cpp and most gateways all offer
one, so a client written against it is portable. Kirinuki configures the base URL and key
(`OPENAI_BASE_URL`, `OPENAI_API_KEY`) with no defaults, so *where* the model runs is a deployment
choice — and pointing it off-box would contradict ADR-0001.

**Spring AI** — the Java library that talks to LLMs (Ollama, OpenAI, Anthropic…) through one interface,
`ChatClient`. Coding against it instead of provider-specific option classes is what makes the app
model-agnostic.

**ChatClient** — Spring AI's main abstraction for sending a prompt and getting a reply. `.entity(...)`
on it gives you structured output.

**yt-dlp** — the command-line tool that downloads video from a URL. A maintained fork of youtube-dl.

**Adaptive streaming** — YouTube stores each quality/codec as a *separate* stream (video-only,
audio-only, at various resolutions) so a player can mix and match to suit bandwidth. yt-dlp downloads
the chosen video and audio streams and merges them.

**Format selector** — the `-f` string telling yt-dlp which stream(s) to grab, written as a fallback
chain (`bestvideo+bestaudio / best`).

**ffmpeg** — the program that does essentially all media manipulation: extract audio, cut, reframe,
subtitle, thumbnail. We never touch pixels in Java; ffmpeg does.

**Subprocess** — a separate program the app launches and waits on. yt-dlp, ffmpeg, and Whisper are run
this way, all through `ProcessRunner`. (Ollama, by contrast, is a *server* we call over HTTP — a warm
process, not a fresh one per request.)

---

## This app's own vocabulary

**Blackboard** — the design where all stages read from and write to one shared store (here, the
`storage/` filesystem) instead of passing data directly to each other. `StorageService` is the door to
it.

**Artifact** — a file a stage produces: `source.mp4`, `audio.wav`, `transcript.json`, `candidates.json`,
`scored.json`, `clips.json`, the clips themselves. The unit of the pipeline.

**Stage** — one step of the pipeline (`PipelineStage`): download, media prep, transcription, candidate
generation, scoring, rendering. Each owns one artifact.

**Skip-if-exists / idempotent** — a stage is skipped when its artifact already exists. Running the
pipeline twice produces the same result without redoing work — that's *idempotent*. This one rule gives
resume-after-crash for free.

**Atomic write** — write to a temporary `.part` file, then rename to the final name only on success. The
rename is atomic (all-or-nothing), so a crash mid-write never leaves a truncated file that `exists()`
would trust.

**Orchestrator** — `PipelineOrchestrator`, the driver that runs the stages in order. It coordinates; the
tools do the work.

**Recovery scan** — on startup, `PipelineRecovery` re-drives any video that's mid-pipeline, resuming from
the interrupted stage (because completed artifacts still exist).

**Candidate** — a proposed clip window (start/end/text) generated deterministically from sentence
boundaries, before any scoring.

**Sub-score vs. overall score** — the model returns *sub-scores* (hook, education, emotion, visual,
virality); the *overall* is a weighted sum computed in Java. Keeps ranking explainable and tunable
without re-prompting.

**Dedup (deduplication)** — dropping near-duplicate clips (windows overlapping >50%) before the final
cut, so the short and long versions of the same moment don't both ship.

**Observation / span / trace** — Micrometer/OpenTelemetry terms. An *observation* is a timed unit of
work; it becomes a *span* in a *trace* — a tree showing how a whole video's pipeline nested and how long
each part took. Viewable in Grafana (Tempo).

**Profile (Spring)** — a named set of configuration (`local`, `gpu`). Same build, different knobs
(model, thread count, ASR size), so a laptop and a 4090 run identically-built code with different
settings.
