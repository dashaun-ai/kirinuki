# Kirinuki

Turn long-form video into short-form clips.

Submit a video URL. Kirinuki downloads it, transcribes it, finds the moments worth clipping, scores
them, and renders vertical clips with burned-in subtitles.

Everything runs locally. Nothing is uploaded to a third party and there is no per-call cost.

## Requirements

* Java 25
* Docker and Docker Compose
* Ollama, with a chat model pulled
* `yt-dlp`, `ffmpeg` and `whisper-ctranslate2` on `PATH`

## Running

```bash
docker compose up -d
./mvnw spring-boot:run
```

The app listens on `:8080`. Submit a video with a `POST` to `/videos` and poll `/videos/{id}` for
progress. Artifacts are written under `storage/`.

## Configuration

Hardware-dependent settings live in profiles rather than code, so the same build runs on a laptop or
a workstation with a GPU:

```bash
SPRING_PROFILES_ACTIVE=gpu ./mvnw spring-boot:run
```
