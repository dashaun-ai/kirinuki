package ai.dashaun.kirinuki.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ai.dashaun.kirinuki.common.ExternalToolException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.common.ToolNotAvailableException;

@Configuration
public class KirinukiHealthIndicators {

    @Bean
    public HealthIndicator storageHealthIndicator(KirinukiStorageProperties properties) {
        return () -> {
            Path root = properties.root().toAbsolutePath();
            try {
                Files.createDirectories(root);
                Path probe = Files.createTempFile(root, "health", ".probe");
                Files.deleteIfExists(probe);
                return Health.up().withDetail("root", root).build();
            } catch (IOException exception) {
                return Health.down().withDetail("root", root)
                        .withException(exception).build();
            }
        };
    }

    @Bean
    public HealthIndicator ffmpegHealthIndicator(KirinukiPipelineProperties properties, ProcessRunner processRunner) {
        return toolHealthIndicator("ffmpeg", properties.media().binary(), List.of("-version"), processRunner);
    }

    @Bean
    public HealthIndicator ytDlpHealthIndicator(KirinukiYtDlpProperties properties, ProcessRunner processRunner) {
        return toolHealthIndicator("yt-dlp", properties.binary(), List.of("--version"), processRunner);
    }

    @Bean
    public HealthIndicator whisperHealthIndicator(KirinukiPipelineProperties properties, ProcessRunner processRunner) {
        return toolHealthIndicator("whisper-ctranslate2", properties.asr().binary(), List.of("--version"), processRunner);
    }

    private HealthIndicator toolHealthIndicator(String name, String binary, List<String> versionArgs,
            ProcessRunner processRunner) {
        return () -> {
            try {
                List<String> command = new ArrayList<>(List.of(binary));
                command.addAll(versionArgs);
                processRunner.run(binary, command, Duration.ofSeconds(5));
                return Health.up().withDetail("binary", binary).build();
            } catch (ToolNotAvailableException | ExternalToolException exception) {
                return Health.down().withDetail("binary", binary).withException(exception).build();
            }
        };
    }
}
