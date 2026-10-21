package ai.dashaun.kirinuki.edit;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.render.SubtitleWriter;

@Component
class SourceLayout implements LayoutRenderer {

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;
    private final SubtitleWriter subtitleWriter;

    SourceLayout(KirinukiPipelineProperties properties, ProcessRunner processRunner, SubtitleWriter subtitleWriter) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.subtitleWriter = subtitleWriter;
    }

    @Override
    public boolean supports(ShotType type) {
        return type == null || type == ShotType.SPEAKER;
    }

    @Override
    public void render(Path source, List<Word> words, double clipStart, double clipDuration, Shot shot, Scene scene,
            Path output) {
        double shotStart = clipStart + Math.max(0, shot.in());
        double shotEnd = clipStart + Math.min(shot.out(), clipDuration);
        double duration = Math.max(0.1, shotEnd - shotStart);

        KirinukiPipelineProperties.Render render = properties.render();
        int width = render.width();
        int height = render.height();
        double zoom = shot.zoom() != null && shot.zoom() > 1 ? shot.zoom() : 1.0;

        Path subtitles = output.resolveSibling(output.getFileName() + ".ass");
        subtitleWriter.write(words, shotStart, shotEnd, subtitles);

        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]scale=w=%d:h=%d:force_original_aspect_ratio=decrease,setsar=1"
                .formatted(width * 2, height * 2));
        if (zoom > 1.0001) {
            int cropW = (int) Math.round(width * 2 / zoom) / 2 * 2;
            int cropH = (int) Math.round(height * 2 / zoom) / 2 * 2;
            filter.append(",crop=%d:%d:(iw-%d)/2:(ih-%d)/2,scale=%d:%d"
                    .formatted(cropW, cropH, cropW, cropH, width * 2, height * 2));
        }
        filter.append(",pad=%d:%d:(ow-iw)/2:(oh-ih)/2".formatted(width * 2, height * 2));
        filter.append(",scale=%d:%d".formatted(width, height));
        filter.append(",subtitles=%s".formatted(FfmpegClient.escapeFilterPath(subtitles)));
        if (shot.headline() != null && !shot.headline().isBlank()) {
            String boldFont = fontfile(properties.edit().boldFontPath());
            if (boldFont != null) {
                filter.append(
                        ",drawtext=fontfile='%s':text='%s':x=(w-text_w)/2:y=140:fontsize=72:fontcolor=white:borderw=4:bordercolor=black"
                                .formatted(FfmpegClient.escapeFilterPath(Path.of(boldFont)),
                                        escapeDrawtext(shot.headline())));
            }
        }
        filter.append(",format=yuv420p");

        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(shotStart),
                "-t", String.valueOf(duration),
                "-i", source.toString(),
                "-vf", filter.toString(),
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

    private String fontfile(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (path.toFile().exists()) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private String escapeDrawtext(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("%", "\\%")
                .replace("\n", " ");
    }
}
