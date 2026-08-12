# Kirinuki

**Long-form video to short-form clips, fully local.**

Paste a YouTube URL. Kirinuki downloads the video, transcribes it, identifies the strongest moments,
scores them with an LLM, renders vertical clips with burned-in subtitles, and generates
platform-tuned captions for TikTok, Reels, Shorts, LinkedIn, X and YouTube.

Everything runs on your machine. No uploads to third parties, no per-call API costs.

## Pipeline

Each video moves through nine stages in order:

1. **Download** the source video via yt-dlp
2. **Media preparation** extracts audio (mono 16kHz WAV)
3. **Scene extraction** detects visual cuts with ffmpeg
4. **Audio extraction** detects silence boundaries
5. **Transcription** runs whisper-ctranslate2 locally
6. **Candidate generation** finds clip-worthy segments from the transcript
7. **Scoring** sends each candidate to an LLM for sub-score analysis, then computes a weighted overall score
8. **Clip rendering** cuts vertical 1080x1920 clips with blurred fill, gradient overlay and subtitles
9. **Content generation** produces per-platform titles, captions, hashtags and calls to action

The pipeline is resumable. If a stage fails, fix the issue and call advance. Completed stages are
skipped automatically based on their artifact files.

## Requirements

* Java 25
* Docker and Docker Compose (Postgres, Grafana LGTM)
* `yt-dlp`, `ffmpeg` and `whisper-ctranslate2` on PATH
* Access to a Sekisho-compatible OpenAI gateway (or any OpenAI-compatible endpoint)

Install the CLI tools:

```bash
pipx install yt-dlp whisper-ctranslate2
sudo apt install ffmpeg    # or brew install ffmpeg
```

## Setup

1. Start the infrastructure:

```bash
docker compose up -d
```

2. Copy the environment template and fill in your credentials:

```bash
cp .envrc.example .envrc
# Edit .envrc with your actual values
source .envrc
```

3. Run the application:

```bash
./mvnw spring-boot:run
```

The app listens on `localhost:8080`. Health check is at `/actuator/health`.

## Usage

### Web dashboard

Open `http://localhost:8080` in your browser. The dashboard lets you:

* Submit a YouTube URL for processing
* Track pipeline progress in real time
* Review rendered clips with score breakdowns
* Approve, reject or edit clips
* Edit generated captions, hashtags and titles per platform
* Regenerate individual content fields with LLM

### REST API

```bash
# Submit a video
curl -X POST http://localhost:8080/videos \
  -H "Content-Type: application/json" \
  -d '{"url": "https://youtu.be/..."}'

# Check status
curl http://localhost:8080/videos/{id}

# Re-drive a stalled pipeline
curl -X POST http://localhost:8080/videos/{id}/advance

# Get artifacts
curl http://localhost:8080/videos/{id}/transcript
curl http://localhost:8080/videos/{id}/candidates
curl http://localhost:8080/videos/{id}/scored
curl http://localhost:8080/videos/{id}/clips
curl http://localhost:8080/videos/{id}/clips/{index}
```

## Configuration

All secrets and environment-specific values are configured via environment variables.
See `.envrc.example` for the full list.

### Profiles

Hardware-dependent settings live in profiles so the same build runs on a laptop or a GPU workstation:

```bash
# Default (local, CPU inference)
./mvnw spring-boot:run

# GPU workstation
SPRING_PROFILES_ACTIVE=gpu ./mvnw spring-boot:run
```

### Scoring weights

The overall clip score is a weighted sum of five sub-scores. Adjust the weights in
`application.yml` under `kirinuki.pipeline.scoring.weights`:

| Sub-score         | Default weight |
|-------------------|----------------|
| Hook              | 3              |
| Educational value | 2              |
| Emotion           | 1              |
| Visual interest   | 1              |
| Virality          | 3              |

### Subtitle rendering

Subtitle appearance is configurable under `kirinuki.pipeline.render`:

| Property              | Default | Description                          |
|-----------------------|---------|--------------------------------------|
| `words-per-caption`   | 3       | Words shown per subtitle frame       |
| `subtitle-font`       | Arial   | Font family                          |
| `subtitle-size`       | 78      | Font size in ASS script coordinates  |
| `subtitle-margin-bottom` | 200  | Distance from the bottom edge        |

## Observability

The app exports traces, metrics and logs via OpenTelemetry. The default Docker Compose includes
Grafana LGTM (Loki, Grafana, Tempo, Mimir) on `localhost:3000`.

Each video is one trace: `kirinuki.pipeline` spans contain `kirinuki.stage` spans, which contain
`kirinuki.tool` spans for external processes and `spring_ai chat_client` spans for LLM calls.

## Project structure

```
ai.dashaun.kirinuki
  config/          Properties records and OTel setup
  common/          Base exception, ProcessRunner, global error handler
  video/           Entity, repository, service, REST controller
  pipeline/        Orchestrator, status enum, stage interface
  pipeline/stage/  Nine pipeline stage implementations
  candidate/       Transcript reader and candidate generation
  scoring/         LLM scoring client and weighted scorer
  render/          Clip renderer, subtitle writer, boundary refiner
  content/         LLM content generation client and models
  review/          Clip review and approval service
  dashboard/       Thymeleaf web dashboard controller
  media/           FFmpeg client, Whisper client, feature extractors
  storage/         Filesystem storage service
```

## License

See [LICENSE](LICENSE) for details.
