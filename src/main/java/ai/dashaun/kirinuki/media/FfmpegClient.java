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
        KirinukiPipelineProperties.Render render = properties.render();
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-ss", String.valueOf(start),
                "-i", source.toString(),
                "-t", String.valueOf(duration),
                "-filter_complex", verticalFilter(render, subtitles),
                "-c:v", "libx264",
                "-preset", render.preset(),
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-movflags", "+faststart",
                target.toString()), render.timeout());
    }

    private String verticalFilter(KirinukiPipelineProperties.Render render, Path subtitles) {
        int width = render.width();
        int height = render.height();
        int blurWidth = Math.max(2, width / 8);
        int blurHeight = Math.max(2, height / 8);
        int zoomed = (int) Math.round(width * render.zoom() / 2) * 2;
        int gradientHeight = height / 6;
        int gradientTop = height - gradientHeight;
        return ("[0:v]split=2[bg][fg];"
                + "[bg]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=8:2,scale=%d:%d[b];"
                + "[fg]scale=%d:-2,crop=%d:ih:(iw-%d)/2:0[f];"
                + "[b][f]overlay=(W-w)/2:(H-h)/2[comp];"
                + "color=black:s=%dx%d,format=rgba,geq=r=0:g=0:b=0:a='220*Y/H'[grad];"
                + "[comp][grad]overlay=0:%d:shortest=1,"
                + "subtitles=%s")
                        .formatted(blurWidth, blurHeight, blurWidth, blurHeight, width, height,
                                zoomed, width, width,
                                width, gradientHeight,
                                gradientTop,
                                subtitles.toString());
    }
}
