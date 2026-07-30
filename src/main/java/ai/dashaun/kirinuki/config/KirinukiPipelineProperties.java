package ai.dashaun.kirinuki.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kirinuki.pipeline")
public record KirinukiPipelineProperties(
        @DefaultValue Media media,
        @DefaultValue Asr asr,
        @DefaultValue Candidates candidates,
        @DefaultValue Scenes scenes,
        @DefaultValue Audio audio,
        @DefaultValue Render render,
        @DefaultValue Scoring scoring) {

    public record Scenes(
            @DefaultValue("0.4") double threshold) {
    }

    public record Audio(
            @DefaultValue("-30") int silenceThreshold,
            @DefaultValue("500ms") Duration silenceMinDuration) {
    }

    public record Media(
            @DefaultValue("ffmpeg") String binary,
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

    public record Render(
            @DefaultValue("1080") int width,
            @DefaultValue("1920") int height,
            @DefaultValue("1.0") double zoom,
            @DefaultValue("23") int crf,
            @DefaultValue("veryfast") String preset,
            @DefaultValue("5") int wordsPerCaption,
            @DefaultValue("Arial") String subtitleFont,
            @DefaultValue("72") int subtitleSize,
            @DefaultValue("200") int subtitleMarginBottom,
            @DefaultValue("400ms") Duration leadIn,
            @DefaultValue("600ms") Duration tail,
            @DefaultValue("30m") Duration timeout) {
    }

    public record Scoring(
            @DefaultValue("8") int topClips,
            @DefaultValue("0") int minScore,
            @DefaultValue("0.0") double temperature,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue Weights weights) {

        public record Weights(
                @DefaultValue("3") int hook,
                @DefaultValue("2") int educationalValue,
                @DefaultValue("1") int emotion,
                @DefaultValue("1") int visualInterest,
                @DefaultValue("3") int virality) {

            public int total() {
                return hook + educationalValue + emotion + visualInterest + virality;
            }
        }
    }
}
