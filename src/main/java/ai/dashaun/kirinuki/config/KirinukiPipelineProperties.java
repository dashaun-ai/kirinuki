package ai.dashaun.kirinuki.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kirinuki.pipeline")
public record KirinukiPipelineProperties(
        @DefaultValue Media media,
        @DefaultValue Asr asr,
        @DefaultValue Scoring scoring) {

    public record Media(
            @DefaultValue("10s") Duration frameInterval,
            @DefaultValue("30m") Duration timeout) {
    }

    public record Asr(
            @DefaultValue("base.en") String model,
            @DefaultValue("cpu") String device,
            @DefaultValue("int8") String computeType,
            @DefaultValue("2h") Duration timeout) {
    }

    public record Scoring(
            @DefaultValue("10") int candidatesPerRequest,
            @DefaultValue("8") int topClips,
            @DefaultValue("30m") Duration timeout) {
    }
}
