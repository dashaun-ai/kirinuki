package ai.dashaun.kirinuki.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class FfmpegClient {

    private static final double TARGET_VERTICAL_ASPECT = 1080.0 / 1920.0;
    private static final double VERTICAL_ASPECT_TOLERANCE = 0.05;
    private static final String LOUDNORM_AUDIO_FILTER = "loudnorm=I=-14:TP=-1.5:LRA=11";

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;

    public FfmpegClient(KirinukiPipelineProperties properties, ProcessRunner processRunner, ObjectMapper objectMapper) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
    }

    public MediaProfile probeMediaProfile(Path source) {
        String binary = "ffprobe";
        String output = processRunner.run(binary, List.of(binary,
                "-v", "error",
                "-show_streams",
                "-of", "json",
                source.toString()), properties.media().timeout());
        return parseMediaProfile(output);
    }

    private MediaProfile parseMediaProfile(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            JsonNode streams = root.path("streams");
            JsonNode videoStream = null;
            JsonNode audioStream = null;
            for (JsonNode stream : streams) {
                String codecType = stream.path("codec_type").asText();
                if ("video".equals(codecType) && videoStream == null) {
                    videoStream = stream;
                } else if ("audio".equals(codecType) && audioStream == null) {
                    audioStream = stream;
                }
            }
            if (videoStream == null) {
                throw new KirinukiException("No video stream found in source");
            }
            int width = videoStream.path("width").asInt();
            int height = videoStream.path("height").asInt();
            double fps = parseFrameRate(videoStream.path("r_frame_rate").asText("0/1"));
            double streamDuration = parseDuration(videoStream.path("duration").asText("0"));
            double formatDuration = parseDuration(root.path("format").path("duration").asText("0"));
            double duration = streamDuration > 0 ? streamDuration : formatDuration;
            return new MediaProfile(width, height, fps, duration, audioStream != null);
        } catch (Exception exception) {
            throw new KirinukiException("Could not parse media profile: " + output, exception);
        }
    }

    private double parseFrameRate(String frameRate) {
        if (frameRate == null || frameRate.isBlank()) {
            return 0.0;
        }
        int slash = frameRate.indexOf('/');
        if (slash < 0) {
            return Double.parseDouble(frameRate);
        }
        double numerator = Double.parseDouble(frameRate.substring(0, slash));
        double denominator = Double.parseDouble(frameRate.substring(slash + 1));
        return denominator == 0 ? 0.0 : numerator / denominator;
    }

    private double parseDuration(String duration) {
        try {
            return Double.parseDouble(duration);
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    public void extractAudio(Path source, Path target) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-i", source.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                target.toString()), properties.media().timeout());
    }

    public List<Double> detectScenes(Path source) {
        String binary = properties.media().binary();
        double threshold = properties.scenes().threshold();
        String output = processRunner.run(binary, List.of(binary,
                "-hide_banner",
                "-nostats",
                "-i", source.toString(),
                "-vf", "select='gt(scene,%s)',metadata=print:file=-".formatted(threshold),
                "-an",
                "-f", "null", "-"), properties.media().timeout());
        return parseScenes(output);
    }

    private List<Double> parseScenes(String output) {
        List<Double> times = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int marker = line.indexOf("pts_time:");
            if (marker >= 0) {
                times.add(Double.parseDouble(line.substring(marker + "pts_time:".length()).strip()));
            }
        }
        return times;
    }

    public List<Silence> detectSilence(Path audio) {
        String binary = properties.media().binary();
        KirinukiPipelineProperties.Audio audioProperties = properties.audio();
        double minDuration = audioProperties.silenceMinDuration().toMillis() / 1000.0;
        String output = processRunner.run(binary, List.of(binary,
                "-hide_banner",
                "-nostats",
                "-i", audio.toString(),
                "-af", "silencedetect=noise=%ddB:d=%s,ametadata=mode=print:file=-"
                        .formatted(audioProperties.silenceThreshold(), minDuration),
                "-f", "null", "-"), properties.media().timeout());
        return parseSilence(output);
    }

    private List<Silence> parseSilence(String output) {
        List<Silence> silences = new ArrayList<>();
        Double start = null;
        for (String line : output.split("\\R")) {
            int startMarker = line.indexOf("lavfi.silence_start=");
            if (startMarker >= 0) {
                start = Double.parseDouble(line.substring(startMarker + "lavfi.silence_start=".length()).strip());
                continue;
            }
            int endMarker = line.indexOf("lavfi.silence_end=");
            if (endMarker >= 0 && start != null) {
                double end = Double.parseDouble(line.substring(endMarker + "lavfi.silence_end=".length()).strip());
                silences.add(new Silence(start, end));
                start = null;
            }
        }
        return silences;
    }

    public void renderVertical(Path source, double start, double duration, Path subtitles, Path target) {
        renderVertical(source, start, duration, subtitles, target, null, null);
    }

    public void renderVertical(Path source, double start, double duration, Path subtitles, Path target,
            FramingPlan plan) {
        renderVertical(source, start, duration, subtitles, target, plan, null);
    }

    public void renderVertical(Path source, double start, double duration, Path subtitles, Path target,
            FramingPlan plan, MediaProfile profile) {
        KirinukiPipelineProperties.Render render = properties.render();
        String binary = properties.media().binary();
        List<String> command = new ArrayList<>(List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(start),
                "-i", source.toString(),
                "-t", String.valueOf(duration),
                "-filter_complex", verticalFilter(render, subtitles, plan, start, profile),
                "-c:v", "libx264",
                "-preset", render.preset(),
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-af", LOUDNORM_AUDIO_FILTER,
                "-movflags", "+faststart",
                "-r", String.valueOf(outputFrameRate(profile, render.maxFps())),
                target.toString()));
        processRunner.run(binary, command, render.timeout());
    }

    public void extractFrame(Path source, double at, int maxWidth, Path output) {
        String binary = properties.media().binary();
        List<String> command = new ArrayList<>(List.of(binary, "-y", "-hide_banner", "-nostats", "-ss",
                String.valueOf(at),
                "-i", source.toString(), "-frames:v", "1", "-q:v", "2"));
        if (maxWidth > 0) {
            command.add("-vf");
            command.add("scale=%d:-2".formatted(maxWidth));
        }
        command.add(output.toString());
        processRunner.run(binary, command, properties.media().timeout());
    }

    public void extractThumbnails(Path source, double interval, int maxWidth, Path outputDir) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-i", source.toString(),
                "-vf", "fps=1/%s,scale=%d:-2".formatted(interval, maxWidth),
                "-q:v", "2",
                outputDir.resolve("frame-%05d.jpg").toString()), properties.media().timeout());
    }

    private double outputFrameRate(MediaProfile profile, int maxFps) {
        if (profile == null || profile.fps() <= 0) {
            return maxFps;
        }
        return Math.min(profile.fps(), maxFps);
    }

    private boolean isSourceVertical(MediaProfile profile) {
        if (profile == null) {
            return false;
        }
        double aspect = profile.aspectRatio();
        return Math.abs(aspect - TARGET_VERTICAL_ASPECT) < VERTICAL_ASPECT_TOLERANCE;
    }

    private String verticalFilter(KirinukiPipelineProperties.Render render, Path subtitles, FramingPlan plan,
            double clipStart, MediaProfile profile) {
        int width = render.width();
        int height = render.height();
        int gradientHeight = height / 6;
        int gradientTop = height - gradientHeight;
        String escapedSubtitles = escapeFilterPath(subtitles);
        if (isSourceVertical(profile) && plan == null) {
            return verticalDirectFilter(width, height, gradientHeight, gradientTop, escapedSubtitles);
        }
        return verticalBlurFillFilter(render, plan, clipStart, width, height, gradientHeight, gradientTop,
                escapedSubtitles);
    }

    private String verticalDirectFilter(int width, int height, int gradientHeight, int gradientTop,
            String escapedSubtitles) {
        return ("[0:v]scale=%d:%d:force_original_aspect_ratio=decrease,setsar=1,pad=%d:%d:(ow-iw)/2:(oh-ih)/2[comp];"
                + "color=black:s=%dx%d,format=rgba,geq=r=0:g=0:b=0:a='220*Y/H'[grad];"
                + "[comp][grad]overlay=0:%d:shortest=1,"
                + "subtitles=%s")
                        .formatted(width, height, width, height,
                                width, gradientHeight,
                                gradientTop,
                                escapedSubtitles);
    }

    private String verticalBlurFillFilter(KirinukiPipelineProperties.Render render, FramingPlan plan,
            double clipStart, int width, int height, int gradientHeight, int gradientTop, String escapedSubtitles) {
        int blurWidth = Math.max(2, width / 8);
        int blurHeight = Math.max(2, height / 8);
        int zoomed = Math.max(width, (int) Math.round(width * render.zoom() / 2) * 2);
        String foreground = plan == null
                ? "[fg]scale=%d:-2,crop=%d:ih:(iw-%d)/2:0[f]".formatted(zoomed, width, width)
                : "[fg]crop=%d:%d:%s:%s,scale=%d:-2[f]".formatted(plan.width(), plan.height(),
                        cropValue(expression(plan.segments(), clipStart, SpeakerSegment::x)),
                        cropValue(expression(plan.segments(), clipStart, SpeakerSegment::y)),
                        zoomed);
        return ("[0:v]split=2[bg][fg];"
                + "[bg]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=8:2,scale=%d:%d[b];"
                + foreground + ";"
                + "[b][f]overlay=(W-w)/2:(H-h)/2[comp];"
                + "color=black:s=%dx%d,format=rgba,geq=r=0:g=0:b=0:a='220*Y/H'[grad];"
                + "[comp][grad]overlay=0:%d:shortest=1,"
                + "subtitles=%s")
                        .formatted(blurWidth, blurHeight, blurWidth, blurHeight, width, height,
                                width, gradientHeight,
                                gradientTop,
                                escapedSubtitles);
    }

    public static String escapeFilterPath(Path path) {
        return path.toAbsolutePath().toString()
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace("%", "\\%")
                .replace(",", "\\,")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace(";", "\\;")
                .replace(" ", "\\ ");
    }

    private String cropValue(String expression) {
        return expression.chars().anyMatch(character -> character == ',' || character == ':')
                ? "'" + expression + "'"
                : expression;
    }

    private String expression(List<SpeakerSegment> segments, double clipStart,
            java.util.function.ToIntFunction<SpeakerSegment> value) {
        if (segments.isEmpty()) {
            return "0";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            if (index == segments.size() - 1) {
                builder.append(value.applyAsInt(segments.get(index)));
            } else {
                double boundary = segments.get(index + 1).from() - clipStart;
                builder.append("if(lt(t,").append(boundary).append("),")
                        .append(value.applyAsInt(segments.get(index))).append(",");
            }
        }
        builder.append(")".repeat(Math.max(0, segments.size() - 1)));
        return builder.toString();
    }
}
