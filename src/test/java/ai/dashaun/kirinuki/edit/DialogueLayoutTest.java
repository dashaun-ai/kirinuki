package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.common.ToolNotAvailableException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.render.SubtitleWriter;
import ai.dashaun.kirinuki.vision.FaceSample;
import ai.dashaun.kirinuki.vision.FaceTrack;
import ai.dashaun.kirinuki.vision.FaceTracks;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.vision.SceneSpeaker;
import ai.dashaun.kirinuki.vision.SpeakerActivity;

class DialogueLayoutTest {

    @TempDir
    Path directory;

    private Path source;
    private KirinukiPipelineProperties properties;
    private DialogueLayout layout;

    @BeforeEach
    void setUp() {
        properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
        ProcessRunner processRunner = new ProcessRunner();
        SubtitleWriter subtitleWriter = new SubtitleWriter(properties);
        layout = new DialogueLayout(properties, processRunner, subtitleWriter);
        source = directory.resolve("source.mp4");
        createSourceVideo(processRunner);
    }

    private void createSourceVideo(ProcessRunner processRunner) {
        try {
            processRunner.run("ffmpeg", List.of("ffmpeg",
                    "-y",
                    "-hide_banner",
                    "-nostats",
                    "-f", "lavfi",
                    "-i", "testsrc=duration=5:size=1920x1080:rate=30",
                    "-f", "lavfi",
                    "-i", "sine=frequency=1000:duration=5",
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-crf", "28",
                    "-c:a", "aac",
                    "-pix_fmt", "yuv420p",
                    source.toString()), java.time.Duration.ofMinutes(1));
        } catch (ToolNotAvailableException exception) {
            source = null;
        }
    }

    @Test
    void should_render_dialogue_2up() throws Exception {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        Scene scene = scene(List.of(
                new FaceTrack(1, List.of(
                        new FaceSample(0.5, 480, 540, 200, 200, 2.0),
                        new FaceSample(2.5, 480, 540, 200, 200, 1.0))),
                new FaceTrack(2, List.of(
                        new FaceSample(3.0, 1440, 540, 200, 200, 2.0),
                        new FaceSample(4.5, 1440, 540, 200, 200, 1.0)))),
                List.of(
                        new SpeakerActivity(0.0, 2.5, "1", 1.0),
                        new SpeakerActivity(2.5, 5.0, "2", 1.0)));
        Shot shot = new Shot(0, 5, ShotBlock.SOURCE, null, null, null, null, null, null, null, ShotType.DIALOGUE_2UP,
                null, List.of("1", "2"), null, null);
        Path output = directory.resolve("dialogue-2up.mp4");

        layout.render(source, List.of(new Word("hello world", 0.0, 5.0)), 0.0, 5.0, shot, scene, output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
    }

    @Test
    void should_render_dialogue_3up() throws Exception {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        Scene scene = scene(List.of(
                new FaceTrack(1, List.of(
                        new FaceSample(0.5, 480, 540, 200, 200, 2.0),
                        new FaceSample(1.0, 480, 540, 200, 200, 1.0))),
                new FaceTrack(2, List.of(
                        new FaceSample(2.0, 960, 540, 200, 200, 2.0),
                        new FaceSample(3.0, 960, 540, 200, 200, 1.0))),
                new FaceTrack(3, List.of(
                        new FaceSample(4.0, 1440, 540, 200, 200, 2.0),
                        new FaceSample(4.5, 1440, 540, 200, 200, 1.0)))),
                List.of(
                        new SpeakerActivity(0.0, 1.5, "1", 1.0),
                        new SpeakerActivity(1.5, 3.5, "2", 1.0),
                        new SpeakerActivity(3.5, 5.0, "3", 1.0)));
        Shot shot = new Shot(0, 5, ShotBlock.SOURCE, null, null, null, null, null, null, null, ShotType.DIALOGUE_3UP,
                null, List.of("1", "2", "3"), null, null);
        Path output = directory.resolve("dialogue-3up.mp4");

        layout.render(source, List.of(new Word("hello world", 0.0, 5.0)), 0.0, 5.0, shot, scene, output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
    }

    private Scene scene(List<FaceTrack> tracks, List<SpeakerActivity> activity) {
        FaceTracks faceTracks = new FaceTracks(1920, 1080, tracks);
        List<SceneSpeaker> speakers = tracks.stream()
                .map(track -> new SceneSpeaker(String.valueOf(track.id()), "track-" + track.id()))
                .toList();
        return new Scene(speakers, activity, null, List.of(), false, faceTracks);
    }
}
