package ai.dashaun.kirinuki.video;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.VideoDownloadException;
import ai.dashaun.kirinuki.config.KirinukiYtDlpProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class YtDlpClient {

    private static final String FORMAT = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best";

    private final KirinukiYtDlpProperties properties;
    private final ObjectMapper objectMapper;

    public YtDlpClient(KirinukiYtDlpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public YouTubeMetadata fetchMetadata(String url) {
        String json = run(List.of(properties.binary(), "--dump-json", "--no-playlist", url),
                properties.metadataTimeout());
        try {
            JsonNode metadata = objectMapper.readTree(json);
            return new YouTubeMetadata(
                    metadata.path("id").asString(),
                    metadata.path("title").asString(),
                    metadata.path("duration").asInt(),
                    metadata.path("uploader").asString(null));
        } catch (JacksonException exception) {
            throw new VideoDownloadException("Could not read yt-dlp metadata for " + url, exception);
        }
    }

    public void download(String url, Path target) {
        run(List.of(properties.binary(),
                "--no-playlist",
                "--no-progress",
                "--force-overwrites",
                "--merge-output-format", "mp4",
                "-f", FORMAT,
                "-o", target.toString(),
                url), properties.downloadTimeout());
    }

    private String run(List<String> command, Duration timeout) {
        Process process = start(command);
        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> standardOutput = drains.submit(() -> readFully(process.getInputStream()));
            Future<String> errorOutput = drains.submit(() -> readFully(process.getErrorStream()));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new VideoDownloadException("yt-dlp timed out after " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new VideoDownloadException(
                        "yt-dlp exited with %d: %s".formatted(process.exitValue(), errorOutput.get().strip()));
            }
            return standardOutput.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new VideoDownloadException("Interrupted while running yt-dlp", exception);
        } catch (ExecutionException exception) {
            throw new VideoDownloadException("Could not read yt-dlp output", exception.getCause());
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new VideoDownloadException("Could not start " + properties.binary() + " — is it on PATH?", exception);
        }
    }

    private String readFully(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
