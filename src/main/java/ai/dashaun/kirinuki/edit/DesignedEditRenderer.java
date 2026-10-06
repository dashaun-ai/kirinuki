package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.render.SubtitleWriter;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;

@Component
public class DesignedEditRenderer {

    private static final Logger log = LoggerFactory.getLogger(DesignedEditRenderer.class);

    private static final String HANDLE = "</> your_javaguy";
    private static final String ACCENT = "#e91e63";
    private static final String BG = "#0a0a0f";

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;
    private final TranscriptReader transcriptReader;
    private final SubtitleWriter subtitleWriter;

    public DesignedEditRenderer(KirinukiPipelineProperties properties, ProcessRunner processRunner,
            TranscriptReader transcriptReader, SubtitleWriter subtitleWriter) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.transcriptReader = transcriptReader;
        this.subtitleWriter = subtitleWriter;
    }

    public void render(Path source, Path transcript, ScoredCandidate scored, EditPlan plan, String title,
            Path output) {
        if (plan.shots() == null || plan.shots().isEmpty()) {
            throw new KirinukiException("No shots in edit plan for clip " + plan.clipIndex());
        }
        Candidate candidate = scored.candidate();
        double clipStart = candidate.start();
        double clipDuration = candidate.end() - candidate.start();
        List<Word> words = transcriptReader.readWords(transcript);

        Path segmentsDir = output.getParent().resolve("segments");
        createDirectory(segmentsDir);

        List<Path> segments = new ArrayList<>();
        for (int index = 0; index < plan.shots().size(); index++) {
            Shot shot = plan.shots().get(index);
            Path segment = segmentsDir.resolve("shot-%03d.mp4".formatted(index));
            renderShot(source, words, clipStart, clipDuration, shot, title, segment);
            segments.add(segment);
        }

        Path concatList = segmentsDir.resolve("concat.txt");
        writeConcatList(segments, concatList);
        concatSegments(concatList, output);
        cleanup(segmentsDir);
    }

    private void renderShot(Path source, List<Word> words, double clipStart, double clipDuration, Shot shot,
            String title, Path output) {
        double shotStart = clipStart + Math.max(0, shot.in());
        double shotEnd = clipStart + Math.min(shot.out(), clipDuration);
        double duration = shotEnd - shotStart;
        if (duration <= 0.1) {
            duration = 0.1;
        }

        log.info("Rendering shot block={} for output={}", shot.block(), output.getFileName());
        if (shot.block() == ShotBlock.SOURCE) {
            renderSourceSegment(source, words, shotStart, shotEnd, shot, output);
        } else {
            renderDesignedSegment(duration, shot, title, output);
        }
    }

    private void renderSourceSegment(Path source, List<Word> words, double shotStart, double shotEnd, Shot shot,
            Path output) {
        double duration = shotEnd - shotStart;
        KirinukiPipelineProperties.Render render = properties.render();
        int width = render.width();
        int height = render.height();
        double zoom = shot.zoom() != null && shot.zoom() > 1 ? shot.zoom() : 1.0;

        Path subtitles = output.resolveSibling(output.getFileName() + ".ass");
        subtitleWriter.write(words, shotStart, shotEnd, subtitles);

        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]scale=w=%d:h=%d:force_original_aspect_ratio=decrease,setsar=1".formatted(width * 2, height * 2));
        if (zoom > 1.0001) {
            int cropW = (int) Math.round(width * 2 / zoom) / 2 * 2;
            int cropH = (int) Math.round(height * 2 / zoom) / 2 * 2;
            filter.append(",crop=%d:%d:(iw-%d)/2:(ih-%d)/2,scale=%d:%d"
                    .formatted(cropW, cropH, cropW, cropH, width * 2, height * 2));
        }
        filter.append(",pad=%d:%d:(ow-iw)/2:(oh-ih)/2".formatted(width * 2, height * 2));
        filter.append(",scale=%d:%d".formatted(width, height));
        filter.append(",subtitles=%s".formatted(escapeFilterPath(subtitles)));
        if (shot.headline() != null && !shot.headline().isBlank()) {
            filter.append(",drawtext=fontfile='%s':text='%s':x=(w-text_w)/2:y=140:fontsize=72:fontcolor=white:borderw=4:bordercolor=black"
                    .formatted(fontfile(properties.edit().boldFontPath()), escapeDrawtext(shot.headline())));
        }
        filter.append(",format=yuv420p");

        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-ss", String.valueOf(shotStart),
                "-t", String.valueOf(duration),
                "-i", source.toString(),
                "-vf", filter.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-b:a", "128k",
                "-ar", "48000",
                "-r", "30",
                "-pix_fmt", "yuv420p",
                output.toString()), properties.media().timeout());
    }

    private void renderDesignedSegment(double duration, Shot shot, String title, Path output) {
        KirinukiPipelineProperties.Render render = properties.render();
        int width = render.width();
        int height = render.height();
        String bodyFontfile = fontfile(properties.edit().fontPath());
        String boldFontfile = fontfile(properties.edit().boldFontPath());

        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]vignette=angle=PI/4.5");

        filter.append(drawtextPlain(HANDLE, 60, 38, "#a0a0a0", false));

        if (title != null && !title.isBlank()) {
            String tag = truncate(title, 32).toUpperCase();
            filter.append(drawtextBox(tag, 120, 18, "white", ACCENT));
        }

        filter.append(drawtextPlain(blockLabel(shot.block()), height - 90, 26, "#606060", false));

        int y = height / 2 - 160;
        if (shot.block() == ShotBlock.SLAM && shot.headline() != null) {
            String text = wrap(shot.headline(), 10);
            filter.append(drawtextWithFont(text, y, 140, "white", true, boldFontfile));
        } else if (shot.block() == ShotBlock.COUNTER && shot.headline() != null) {
            filter.append(drawtextWithFont(shot.headline(), y, 160, ACCENT, true, boldFontfile));
            y += 190;
            if (shot.subline() != null) {
                filter.append(drawtextWithFont(shot.subline(), y, 60, "white", false, bodyFontfile));
            }
        } else {
            if (shot.headline() != null) {
                String text = wrap(shot.headline(), 14);
                filter.append(drawtextWithFont(text, y, 96, "white", true, boldFontfile));
                y += 130;
            }
            if (shot.subline() != null) {
                filter.append(drawtextWithFont(shot.subline(), y, 56, ACCENT, false, bodyFontfile));
                y += 90;
            }
        }

        if (shot.lines() != null && !shot.lines().isEmpty()) {
            String lines = String.join("\\n", shot.lines());
            filter.append(drawtextWithFont(lines, y + 30, 46, "white", false, bodyFontfile));
        }

        if (shot.logos() != null && !shot.logos().isEmpty() && shot.block() == ShotBlock.LOGO_WALL) {
            String logos = String.join("    ", shot.logos()).toUpperCase();
            filter.append(drawtextWithFont(logos, height - 180, 48, "white", false, boldFontfile));
        }

        filter.append(",format=yuv420p");

        String colorInput = "color=c=%s:s=%dx%d:d=%s:r=30".formatted(BG, width, height, duration);
        log.debug("Designed segment filter for {}: input={} filter={}", output.getFileName(), colorInput, filter);
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-f", "lavfi",
                "-i", colorInput,
                "-f", "lavfi",
                "-i", "anullsrc=channel_layout=mono:sample_rate=48000",
                "-t", String.valueOf(duration),
                "-vf", filter.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-b:a", "128k",
                "-pix_fmt", "yuv420p",
                "-r", "30",
                "-shortest",
                output.toString()), properties.media().timeout());
    }

    private String drawtextPlain(String text, int y, int size, String color, boolean strong) {
        return drawtext(text, y, size, color, strong, null, null);
    }

    private String drawtextWithFont(String text, int y, int size, String color, boolean strong, String fontfile) {
        return drawtext(text, y, size, color, strong, fontfile, null);
    }

    private String drawtextBox(String text, int y, int size, String color, String boxColor) {
        return drawtext(text, y, size, color, false, null, boxColor);
    }

    private String drawtext(String text, int y, int size, String color, boolean strong, String fontfile, String boxColor) {
        String escaped = escapeDrawtext(text);
        StringBuilder params = new StringBuilder();
        if (fontfile != null && !fontfile.isEmpty()) {
            params.append("fontfile='").append(escapeFilterPath(Path.of(fontfile))).append("'");
        } else {
            params.append("font='DejaVu Sans'");
        }
        params.append(":text='").append(escaped).append("'");
        params.append(":x=(w-text_w)/2:y=").append(y);
        params.append(":fontsize=").append(size);
        params.append(":fontcolor=").append(color);
        if (strong) {
            params.append(":borderw=5:bordercolor=black:shadowcolor=black@0.6:shadowx=4:shadowy=4");
        }
        if (boxColor != null && !boxColor.isEmpty()) {
            params.append(":box=1:boxcolor=").append(boxColor).append(":boxborderw=12");
        }
        return ",drawtext=" + params;
    }

    private String blockLabel(ShotBlock block) {
        return block.name().replace("_", " ");
    }

    private String wrap(String text, int maxWordsPerLine) {
        String[] words = text.split("\\s+");
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0 && i % maxWordsPerLine == 0) {
                wrapped.append("\\n");
            } else if (i > 0) {
                wrapped.append(" ");
            }
            wrapped.append(words[i]);
        }
        return wrapped.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private String escapeDrawtext(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("%", "\\%")
                .replace("\n", " ");
    }

    private String escapeFilterPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
    }

    private String fontfile(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.exists(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private void concatSegments(Path concatList, Path output) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatList.toString(),
                "-c", "copy",
                output.toString()), Duration.ofMinutes(10));
    }

    private void writeConcatList(List<Path> segments, Path target) {
        StringBuilder list = new StringBuilder();
        for (Path segment : segments) {
            list.append("file '").append(segment.toAbsolutePath()).append("'\n");
        }
        try {
            Files.writeString(target, list.toString());
        } catch (IOException exception) {
            throw new KirinukiException("Could not write concat list " + target, exception);
        }
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new KirinukiException("Could not create directory " + path, exception);
        }
    }

    private void cleanup(Path segmentsDir) {
        try {
            Files.walk(segmentsDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            log.warn("Could not delete {} during cleanup", path, exception);
                        }
                    });
        } catch (IOException exception) {
            log.warn("Could not clean up segments directory {}", segmentsDir, exception);
        }
    }
}
