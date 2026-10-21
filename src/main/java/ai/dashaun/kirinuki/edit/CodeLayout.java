package ai.dashaun.kirinuki.edit;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.render.SubtitleWriter;

@Component
class CodeLayout implements LayoutRenderer {

    private static final Logger log = LoggerFactory.getLogger(CodeLayout.class);

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;
    private final SubtitleWriter subtitleWriter;

    CodeLayout(KirinukiPipelineProperties properties, ProcessRunner processRunner, SubtitleWriter subtitleWriter) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.subtitleWriter = subtitleWriter;
    }

    @Override
    public boolean supports(ShotType type) {
        return type == ShotType.CODE;
    }

    @Override
    public void render(Path source, List<Word> words, double clipStart, double clipDuration, Shot shot, Scene scene,
            Path output) {
        if (shot.focus() == null) {
            log.warn("CODE shot has no focus region — falling back to source layout");
            throw new KirinukiException("CODE shot missing focus region");
        }
        double shotStart = clipStart + Math.max(0, shot.in());
        double shotEnd = clipStart + Math.min(shot.out(), clipDuration);
        double duration = Math.max(0.1, shotEnd - shotStart);

        KirinukiPipelineProperties.Render render = properties.render();
        int width = render.width();
        int height = render.height();

        Path subtitles = output.resolveSibling(output.getFileName() + ".ass");
        subtitleWriter.write(words, shotStart, shotEnd, subtitles);

        Region focus = shot.focus();
        String crop = "crop=iw*%f:ih*%f:iw*%f:ih*%f".formatted(focus.w(), focus.h(), focus.x(), focus.y());
        String scaleBlur = "scale=%d:%d:force_original_aspect_ratio=decrease,setsar=1".formatted(width * 2, height * 2);
        String pad = "pad=%d:%d:(ow-iw)/2:(oh-ih)/2".formatted(width * 2, height * 2);
        String scaleFinal = "scale=%d:%d".formatted(width, height);
        String subtitlesFilter = "subtitles=%s".formatted(FfmpegClient.escapeFilterPath(subtitles));
        String filter = "[0:v]%s,%s,%s,%s,%s,format=yuv420p".formatted(crop, scaleBlur, pad, scaleFinal,
                subtitlesFilter);

        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(shotStart),
                "-t", String.valueOf(duration),
                "-i", source.toString(),
                "-vf", filter,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-af", "loudnorm=I=-14:TP=-1.5:LRA=11",
                "-b:a", "128k",
                "-ar", "48000",
                "-r", "30",
                "-pix_fmt", "yuv420p",
                output.toString()), properties.media().timeout());
    }
}
