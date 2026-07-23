package ai.dashaun.kirinuki.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kirinuki.pipeline")
public record KirinukiPipelineProperties(
        @DefaultValue Media media,
        @DefaultValue Asr asr,
        @DefaultValue Candidates candidates,
        @DefaultValue Scoring scoring) {

    public record Media(
            @DefaultValue("ffmpeg") String binary,
            @DefaultValue("10s") Duration frameInterval,
            @DefaultValue("30m") Duration timeout) {
    }

    public record Asr(
            @DefaultValue("whisper-ctranslate2") String binary,
            @DefaultValue("base.en") String model,
            @DefaultValue("cpu") String device,
            @DefaultValue("int8") String computeType,
            @DefaultValue("2h") Duration timeout) {
    }

    public record Candidates(
            @DefaultValue("20s") Duration minDuration,
            @DefaultValue("60s") Duration maxDuration,
            @DefaultValue("40") int maxCandidates) {
    }

    public record Scoring(
            @DefaultValue("10") int candidatesPerRequest,
            @DefaultValue("8") int topClips,
            @DefaultValue("30m") Duration timeout) {
    }
}
