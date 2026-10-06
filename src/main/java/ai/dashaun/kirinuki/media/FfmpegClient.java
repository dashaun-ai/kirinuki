package ai.dashaun.kirinuki.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class FfmpegClient {

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;

    public FfmpegClient(KirinukiPipelineProperties properties, ProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    public void extractAudio(Path source, Path target) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
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
        renderVertical(source, start, duration, subtitles, target, null);
    }

    public void renderVertical(Path source, double start, double duration, Path subtitles, Path target,
            FramingPlan plan) {
        KirinukiPipelineProperties.Render render = properties.render();
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-ss", String.valueOf(start),
                "-i", source.toString(),
                "-t", String.valueOf(duration),
                "-filter_complex", verticalFilter(render, subtitles, plan, start),
                "-c:v", "libx264",
                "-preset", render.preset(),
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-movflags", "+faststart",
                target.toString()), render.timeout());
    }

    public void extractFrame(Path source, double at, int maxWidth, Path output) {
        String binary = properties.media().binary();
        List<String> command = new ArrayList<>(List.of(binary, "-y", "-ss", String.valueOf(at),
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
                "-i", source.toString(),
                "-vf", "fps=1/%s,scale=%d:-2".formatted(interval, maxWidth),
                "-q:v", "2",
                outputDir.resolve("frame-%05d.jpg").toString()), properties.media().timeout());
    }

    private String verticalFilter(KirinukiPipelineProperties.Render render, Path subtitles, FramingPlan plan,
            double clipStart) {
        int width = render.width();
        int height = render.height();
        int blurWidth = Math.max(2, width / 8);
        int blurHeight = Math.max(2, height / 8);
        int zoomed = (int) Math.round(width * render.zoom() / 2) * 2;
        int gradientHeight = height / 6;
        int gradientTop = height - gradientHeight;
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
                                subtitles.toString());
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
