package ai.dashaun.kirinuki.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kirinuki.yt-dlp")
public record KirinukiYtDlpProperties(
        @DefaultValue("yt-dlp") String binary,
        @DefaultValue("60s") Duration metadataTimeout,
        @DefaultValue("30m") Duration downloadTimeout) {
}
