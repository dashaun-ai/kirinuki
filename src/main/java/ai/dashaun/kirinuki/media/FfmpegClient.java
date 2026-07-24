package ai.dashaun.kirinuki.media;

import java.nio.file.Path;
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

    public void thumbnail(Path clip, double offset, Path target) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-ss", String.valueOf(offset),
                "-i", clip.toString(),
                "-frames:v", "1",
                target.toString()), properties.media().timeout());
    }

    private String verticalFilter(KirinukiPipelineProperties.Render render, Path subtitles) {
        int width = render.width();
        int height = render.height();
        int blurWidth = Math.max(2, width / 8);
        int blurHeight = Math.max(2, height / 8);
        int zoomed = (int) Math.round(width * render.zoom() / 2) * 2;
        return ("[0:v]split=2[bg][fg];"
                + "[bg]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=8:2,scale=%d:%d[b];"
                + "[fg]scale=%d:-2,crop=%d:ih:(iw-%d)/2:0[f];"
                + "[b][f]overlay=(W-w)/2:(H-h)/2,subtitles=%s")
                        .formatted(blurWidth, blurHeight, blurWidth, blurHeight, width, height,
                                zoomed, width, width, subtitles.toString());
    }
}
