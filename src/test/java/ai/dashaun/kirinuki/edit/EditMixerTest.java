package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.common.ToolNotAvailableException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class EditMixerTest {

    @TempDir
    Path directory;

    private Path source;
    private EditMixer mixer;

    @BeforeEach
    void setUp() throws IOException {
        KirinukiPipelineProperties properties = new KirinukiPipelineProperties(
                new KirinukiPipelineProperties.Media("ffmpeg", Duration.ofMinutes(5)),
                null, null, null, null,
                new KirinukiPipelineProperties.Render("blur-fill", 480, 270, 1.0, 28, "ultrafast", 3, "Arial", 78,
                        false, 200, Duration.ofMillis(400), Duration.ofMillis(600),
                        Duration.ofMillis(1200), 30, Duration.ofMinutes(5),
                        new KirinukiPipelineProperties.Render.Reframe(3.0, 1.25, 480, 480, 8)),
                null, null, null, null,
                new KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest", 0.4, 2048, 0.8, 6, 12,
                        0.6, null, null, null, null, null, null));
        ProcessRunner processRunner = new ProcessRunner();
        mixer = new EditMixer(processRunner, properties);
        source = directory.resolve("source.mp4");
        createSourceVideo();
    }

    private void createSourceVideo() {
        try {
            new ProcessRunner().run("ffmpeg", List.of("ffmpeg",
                    "-y",
                    "-f", "lavfi",
                    "-i", "testsrc=duration=6:size=480x270:rate=30",
                    "-f", "lavfi",
                    "-i", "sine=frequency=1000:duration=6",
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-crf", "28",
                    "-c:a", "aac",
                    "-pix_fmt", "yuv420p",
                    source.toString()), Duration.ofMinutes(1));
        } catch (ToolNotAvailableException exception) {
            source = null;
        }
    }

    @Test
    void should_mix_segments_with_crossfade_and_source_audio_bed() throws IOException {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        List<Path> segments = new ArrayList<>();
        segments.add(slice(source, 0, 2, "segment-0.mp4"));
        segments.add(slice(source, 2, 2, "segment-1.mp4"));
        segments.add(slice(source, 4, 2, "segment-2.mp4"));

        List<Shot> shots = List.of(
                new Shot(0, 2, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null, null, null,
                        null),
                new Shot(2, 4, ShotBlock.SLAM, "SLAM", null, null, null, null, null, null, null, null, null, null,
                        null),
                new Shot(4, 6, ShotBlock.OUTRO, "Thanks", null, null, null, null, null, null, null, null, null, null,
                        null));

        Path output = directory.resolve("mixed.mp4");

        mixer.mix(source, 0.0, segments, shots, output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
        double expected = 6.0 - 2 * 0.2;
        assertThat(probeDuration(output)).isBetween(expected - 0.5, expected + 0.5);
    }

    @Test
    void should_mix_two_segments_with_crossfade() throws IOException {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        List<Path> segments = new ArrayList<>();
        segments.add(slice(source, 0, 3, "segment-0.mp4"));
        segments.add(slice(source, 3, 3, "segment-1.mp4"));

        List<Shot> shots = List.of(
                new Shot(0, 3, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null, null, null,
                        null),
                new Shot(3, 6, ShotBlock.SLAM, "SLAM", null, null, null, null, null, null, null, null, null, null,
                        null));

        Path output = directory.resolve("mixed-two.mp4");

        mixer.mix(source, 0.0, segments, shots, output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
        double expected = 6.0 - 0.2;
        assertThat(probeDuration(output)).isBetween(expected - 0.5, expected + 0.5);
    }

    @Test
    void should_mix_single_segment() throws IOException {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        List<Path> segments = List.of(slice(source, 0, 3, "segment-0.mp4"));
        List<Shot> shots = List.of(
                new Shot(0, 3, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null, null, null,
                        null));

        Path output = directory.resolve("mixed-single.mp4");

        mixer.mix(source, 0.0, segments, shots, output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
        assertThat(probeDuration(output)).isBetween(2.7, 3.3);
    }

    private Path slice(Path input, double start, double duration, String name) {
        Path output = directory.resolve(name);
        new ProcessRunner().run("ffmpeg", List.of("ffmpeg",
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(start),
                "-t", String.valueOf(duration),
                "-i", input.toString(),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "28",
                "-c:a", "aac",
                "-pix_fmt", "yuv420p",
                output.toString()), Duration.ofMinutes(1));
        return output;
    }

    private double probeDuration(Path file) {
        String output = new ProcessRunner().run("ffprobe", List.of("ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                file.toString()), Duration.ofMinutes(1));
        return Double.parseDouble(output.strip());
    }
}
