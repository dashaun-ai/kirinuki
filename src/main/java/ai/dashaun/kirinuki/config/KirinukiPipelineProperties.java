package ai.dashaun.kirinuki.config;

import java.time.Duration;
import java.util.List;

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
        @DefaultValue Scoring scoring,
        @DefaultValue Content content,
        @DefaultValue Visual visual,
        @DefaultValue Screen screen,
        @DefaultValue Edit edit) {

    public record Scenes(
            @DefaultValue("0.4") double threshold) {
    }

    public record Visual(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5s") Duration sampleInterval,
            @DefaultValue("0.6") double minConfidence,
            @DefaultValue("0.03") double minFaceScale) {
    }

    public record Screen(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration interval,
            @DefaultValue("1s") Duration minChangeGap,
            @DefaultValue("10m") Duration timeout) {
    }

    public record Content(
            @DefaultValue("0.7") double temperature,
            @DefaultValue("1024") int maxTokens) {
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
            @DefaultValue("{audio} --model {model} --device {device} --compute_type {computeType} --word_timestamps True --output_format json --output_dir {outputDir}") String args,
            @DefaultValue("2h") Duration timeout) {
    }

    public record Candidates(
            @DefaultValue("20s") Duration minDuration,
            @DefaultValue("60s") Duration maxDuration,
            @DefaultValue("40") int maxCandidates) {
    }

    public record Render(
            @DefaultValue("blur-fill") String framing,
            @DefaultValue("1080") int width,
            @DefaultValue("1920") int height,
            @DefaultValue("1.0") double zoom,
            @DefaultValue("23") int crf,
            @DefaultValue("veryfast") String preset,
            @DefaultValue("3") int wordsPerCaption,
            @DefaultValue("Arial") String subtitleFont,
            @DefaultValue("78") int subtitleSize,
            @DefaultValue("false") boolean subtitleUppercase,
            @DefaultValue("200") int subtitleMarginBottom,
            @DefaultValue("400ms") Duration leadIn,
            @DefaultValue("600ms") Duration tail,
            @DefaultValue("1200ms") Duration snapTolerance,
            @DefaultValue("30") int maxFps,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue Reframe reframe) {

        public record Reframe(
                @DefaultValue("3.0") double margin,
                @DefaultValue("1.25") double aspect,
                @DefaultValue("480") int minWidth,
                @DefaultValue("480") int minHeight,
                @DefaultValue("8") double minSegmentSeconds) {
        }
    }

    public record Scoring(
            @DefaultValue("8") int topClips,
            @DefaultValue("0") int minScore,
            @DefaultValue("0.0") double temperature,
            @DefaultValue("2048") int maxTokens,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue Weights weights) {

        public record Weights(
                @DefaultValue("3") int hook,
                @DefaultValue("2") int educationalValue,
                @DefaultValue("1") int emotion,
                @DefaultValue("3") int virality) {

            public int total() {
                return hook + educationalValue + emotion + virality;
            }
        }
    }

    public record Edit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean autoRender,
            @DefaultValue("3") int topClips,
            @DefaultValue("spring") String theme,
            @DefaultValue("${EDIT_PLAN_MODEL:${CHAT_MODEL:llama4:latest}}") String planModel,
            @DefaultValue("0.4") double planTemperature,
            @DefaultValue("4096") int planMaxTokens,
            @DefaultValue("0.8") double minShotSeconds,
            @DefaultValue("6") double maxShotSeconds,
            @DefaultValue("12") double maxSourceSeconds,
            @DefaultValue("0.6") double maxDesignedRatio,
            @DefaultValue("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf") String fontPath,
            @DefaultValue("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf") String boldFontPath,
            @DefaultValue("${user.home}/.kimi-code/plugins/managed/shenghua/skill") String kineticSkillDir,
            @DefaultValue("${user.home}/.kimi-code/plugins/managed/shenghua/skill/examples/kits/northwind") String kineticKit,
            @DefaultValue("kinetic") String kineticTemplate,
            @DefaultValue Assets assets) {

        public record Assets(
                @DefaultValue("commons.wikimedia.org,upload.wikimedia.org,spring.io") List<String> allowedHosts,
                @DefaultValue("30d") Duration cacheTtl) {
        }
    }

}

