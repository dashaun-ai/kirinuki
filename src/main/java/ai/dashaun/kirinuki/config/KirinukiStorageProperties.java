package ai.dashaun.kirinuki.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kirinuki.storage")
public record KirinukiStorageProperties(Path root) {
}
