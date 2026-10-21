package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.common.ToolNotAvailableException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Edit.Renderer;
import ai.dashaun.kirinuki.render.SubtitleWriter;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import tools.jackson.databind.json.JsonMapper;

class DesignedEditRendererTest {

    @TempDir
    Path directory;

    private Path source;
    private Path transcript;
    private DesignedEditRenderer renderer;

    @BeforeEach
    void setUp() throws IOException {
        KirinukiPipelineProperties properties = new KirinukiPipelineProperties(
                new KirinukiPipelineProperties.Media("ffmpeg", java.time.Duration.ofMinutes(5)),
                null, null, null, null,
                new KirinukiPipelineProperties.Render("blur-fill", 1080, 1920, 1.0, 28, "ultrafast", 3, "Arial", 78,
                        false, 200, java.time.Duration.ofMillis(400), java.time.Duration.ofMillis(600),
                        java.time.Duration.ofMillis(1200), 30, java.time.Duration.ofMinutes(5),
                        new KirinukiPipelineProperties.Render.Reframe(3.0, 1.25, 480, 480, 8, 0.05)),
                null, null, null, null,
                new KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest", 0.4, 2048, 0.8, 6, 12,
                        0.6, null, null, null, null, null, null, Renderer.HYBRID, true, Duration.ofMinutes(30)));
        JsonMapper objectMapper = new JsonMapper();
        SubtitleWriter subtitleWriter = new SubtitleWriter(properties);
        ProcessRunner processRunner = new ProcessRunner();
        SourceLayout sourceLayout = new SourceLayout(properties, processRunner, subtitleWriter);
        KineticRenderer kineticRenderer = new KineticRenderer(processRunner, properties);
        renderer = new DesignedEditRenderer(properties, processRunner, new TranscriptReader(objectMapper),
                subtitleWriter, List.of(sourceLayout), kineticRenderer, objectMapper,
                new EditMixer(processRunner, properties));
        source = directory.resolve("source.mp4");
        transcript = directory.resolve("transcript.json");
        createSourceVideo();
        createTranscript();
    }

    private void createTranscript() throws IOException {
        Files.writeString(transcript, """
                {"segments":[{"words":[
                {"word":"hello","start":0.0,"end":0.5},
                {"word":"world","start":0.5,"end":1.0},
                {"word":"this","start":1.0,"end":1.5},
                {"word":"is","start":1.5,"end":2.0},
                {"word":"a","start":2.0,"end":2.5},
                {"word":"test","start":2.5,"end":3.0},
                {"word":"clip","start":3.0,"end":3.5},
                {"word":"thanks","start":3.5,"end":4.0}
                ]}]}""");
    }

    private void createSourceVideo() {
        try {
            new ProcessRunner().run("ffmpeg", List.of("ffmpeg",
                    "-y",
                    "-f", "lavfi",
                    "-i", "testsrc=duration=10:size=1920x1080:rate=30",
                    "-f", "lavfi",
                    "-i", "sine=frequency=1000:duration=10",
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
    void should_render_designed_edit_from_plan() throws IOException {
        assumeTrue(source != null && Files.exists(source), "ffmpeg not available");
        ScoredCandidate scored = new ScoredCandidate(new Candidate(1, 0.0, 5.0, 0, 0, "hello world"),
                new CandidateScore(8, 8, 8, 8, List.of(), "reason"), 80);
        EditPlan plan = new EditPlan(1, "spring", List.of(
                new Shot(0, 2, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null, null, null,
                        null),
                new Shot(2, 4, ShotBlock.SLAM, "SLAM", null, null, null, null, null, null, null, null, null, null,
                        null),
                new Shot(4, 5, ShotBlock.OUTRO, "Thanks", null, null, null, null, null, null, null, null, null, null,
                        null)), List.of());
        Path output = directory.resolve("designed").resolve("designed.mp4");

        renderer.render(source, transcript, scored, plan, "Test Title", output);

        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
    }
}
