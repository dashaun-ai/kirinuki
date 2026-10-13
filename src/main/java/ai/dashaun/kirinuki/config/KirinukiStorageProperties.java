package ai.dashaun.kirinuki.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kirinuki.storage")
public record KirinukiStorageProperties(@DefaultValue("./storage") Path root) {
}
