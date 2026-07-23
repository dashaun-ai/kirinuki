package ai.dashaun.kirinuki.video;

import java.nio.file.Path;
import java.util.List;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.ExternalToolException;
import ai.dashaun.kirinuki.common.ProcessRunner;
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
    private final ProcessRunner processRunner;

    public YtDlpClient(KirinukiYtDlpProperties properties, ObjectMapper objectMapper, ProcessRunner processRunner) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.processRunner = processRunner;
    }

    @Retryable(value = ExternalToolException.class, maxRetries = 2, delay = 2000, multiplier = 2)
    public YouTubeMetadata fetchMetadata(String url) {
        String json = processRunner.run(properties.binary(),
                List.of(properties.binary(), "--dump-json", "--no-playlist", url),
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

    @Retryable(value = ExternalToolException.class, maxRetries = 2, delay = 5000, multiplier = 2)
    public void download(String url, Path target) {
        processRunner.run(properties.binary(), List.of(properties.binary(),
                "--no-playlist",
                "--no-progress",
                "--force-overwrites",
                "--merge-output-format", "mp4",
                "-f", FORMAT,
                "-o", target.toString(),
                url), properties.downloadTimeout());
    }
}
