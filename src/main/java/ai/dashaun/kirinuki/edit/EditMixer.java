package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class EditMixer {

    private static final Logger log = LoggerFactory.getLogger(EditMixer.class);

    private static final double TRANSITION_SECONDS = 0.2;
    private static final String LOUDNORM = "loudnorm=I=-14:TP=-1.5:LRA=11";

    private final ProcessRunner processRunner;
    private final KirinukiPipelineProperties properties;

    public EditMixer(ProcessRunner processRunner, KirinukiPipelineProperties properties) {
        this.processRunner = processRunner;
        this.properties = properties;
    }

    public void mix(Path source, double clipStart, List<Path> segments, List<Shot> shots, Path output) {
        if (segments.isEmpty()) {
            throw new KirinukiException("No segments to mix");
        }
        if (segments.size() != shots.size()) {
            throw new KirinukiException("Segment count (%d) does not match shot count (%d)"
                    .formatted(segments.size(), shots.size()));
        }

        createDirectory(output.getParent());
        List<Double> durations = probeDurations(segments);
        double transition = transitionDuration(durations);

        double bedStart = clipStart + shots.get(0).in();
        double bedDuration = shots.get(shots.size() - 1).out() - shots.get(0).in();
        Path bed = output.resolveSibling(output.getFileName().toString() + ".bed.wav");

        try {
            extractSourceAudio(source, bedStart, bedDuration, bed);
            String filter = buildFilter(segments.size(), durations, shots, transition);
            runFfmpeg(segments, bed, filter, output);
        } finally {
            deleteIfExists(bed);
        }
    }

    private String buildFilter(int segmentCount, List<Double> durations, List<Shot> shots, double transition) {
        int bedInput = segmentCount;
        double bedOffset = shots.get(0).in();
        StringBuilder filter = new StringBuilder();

        for (int index = 0; index < segmentCount; index++) {
            double offset = shots.get(index).in() - bedOffset;
            double duration = durations.get(index);
            filter.append("[%d:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono[a%d];"
                    .formatted(index, index));
            filter.append("[%d:a]atrim=start=%.3f:duration=%.3f,asetpts=PTS-STARTPTS,"
                    .formatted(bedInput, offset, duration));
            filter.append("aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono[bed%d];"
                    .formatted(index));
            filter.append("[a%d][bed%d]amix=inputs=2:duration=first:dropout_transition=0[mixed%d];"
                    .formatted(index, index, index));
        }

        if (segmentCount == 1) {
            filter.append("[0:v]format=yuv420p[vout];");
        } else if (segmentCount == 2) {
            filter.append("[0:v][1:v]xfade=transition=fade:duration=%.3f:offset=%.3f[vout];"
                    .formatted(transition, durations.get(0) - transition));
        } else {
            filter.append("[0:v][1:v]xfade=transition=fade:duration=%.3f:offset=%.3f[vt0];"
                    .formatted(transition, durations.get(0) - transition));
            for (int index = 1; index < segmentCount - 1; index++) {
                double offset = cumulativeDuration(durations, index) - (index + 1) * transition;
                filter.append("[vt%d][%d:v]xfade=transition=fade:duration=%.3f:offset=%.3f"
                        .formatted(index - 1, index + 1, transition, offset));
                if (index < segmentCount - 2) {
                    filter.append("[vt%d];".formatted(index));
                } else {
                    filter.append("[vout];");
                }
            }
        }

        if (segmentCount == 1) {
            filter.append("[mixed0]%s[aout]".formatted(LOUDNORM));
        } else if (segmentCount == 2) {
            filter.append("[mixed0][mixed1]acrossfade=d=%.3f[amixed];".formatted(transition));
            filter.append("[amixed]%s[aout]".formatted(LOUDNORM));
        } else {
            filter.append("[mixed0][mixed1]acrossfade=d=%.3f[at0];".formatted(transition));
            for (int index = 1; index < segmentCount - 1; index++) {
                filter.append("[at%d][mixed%d]acrossfade=d=%.3f"
                        .formatted(index - 1, index + 1, transition));
                if (index < segmentCount - 2) {
                    filter.append("[at%d];".formatted(index));
                } else {
                    filter.append("[amixed];");
                }
            }
            filter.append("[amixed]%s[aout]".formatted(LOUDNORM));
        }

        return filter.toString();
    }

    private double cumulativeDuration(List<Double> durations, int upToIndex) {
        double sum = 0.0;
        for (int index = 0; index <= upToIndex; index++) {
            sum += durations.get(index);
        }
        return sum;
    }

    private double transitionDuration(List<Double> durations) {
        double minimum = durations.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        return Math.min(TRANSITION_SECONDS, minimum / 2.0);
    }

    private List<Double> probeDurations(List<Path> segments) {
        List<Double> durations = new ArrayList<>();
        for (Path segment : segments) {
            durations.add(probeDuration(segment));
        }
        return durations;
    }

    private double probeDuration(Path file) {
        String output = processRunner.run("ffprobe", List.of("ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.toString()), properties.media().timeout());
        try {
            return Double.parseDouble(output.strip());
        } catch (NumberFormatException exception) {
            throw new KirinukiException("Could not probe duration of " + file + ": " + output, exception);
        }
    }

    private void extractSourceAudio(Path source, double start, double duration, Path target) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(start),
                "-t", String.valueOf(duration),
                "-i", source.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                target.toString()), properties.media().timeout());
    }

    private void runFfmpeg(List<Path> segments, Path bed, String filter, Path output) {
        KirinukiPipelineProperties.Render render = properties.render();
        List<String> command = new ArrayList<>();
        command.add(properties.media().binary());
        command.add("-y");
        command.add("-hide_banner");
        command.add("-nostats");
        for (Path segment : segments) {
            command.add("-i");
            command.add(segment.toString());
        }
        command.add("-i");
        command.add(bed.toString());
        command.add("-filter_complex");
        command.add(filter);
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add("[aout]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(render.preset());
        command.add("-crf");
        command.add(String.valueOf(render.crf()));
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-ar");
        command.add("48000");
        command.add("-r");
        command.add("30");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(output.toString());
        processRunner.run(properties.media().binary(), command, properties.media().timeout());
    }

    private void createDirectory(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new KirinukiException("Could not create directory " + path, exception);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not delete temporary bed file {}", path, exception);
        }
    }
}
