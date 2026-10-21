package ai.dashaun.kirinuki.pipeline.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiStorageProperties;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.vision.FaceSample;
import ai.dashaun.kirinuki.vision.FaceTrack;
import ai.dashaun.kirinuki.vision.FaceTracks;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.vision.ScreenAnalyzer;
import ai.dashaun.kirinuki.vision.SpeakerTracker;
import tools.jackson.databind.json.JsonMapper;

class SceneAnalysisStageTest {

    @TempDir
    Path tempDir;

    private final ProcessRunner processRunner = new ProcessRunner();
    private final JsonMapper objectMapper = new JsonMapper();

    @Test
    void should_combine_faces_transcript_and_screen_analysis_into_scene_json() throws Exception {
        String videoId = UUID.randomUUID().toString();
        StorageService storageService = new StorageService(new KirinukiStorageProperties(tempDir));
        ScreenAnalyzer screenAnalyzer = new ScreenAnalyzer(processRunner, properties(), objectMapper);
        SpeakerTracker speakerTracker = new SpeakerTracker(properties());
        TranscriptReader transcriptReader = new TranscriptReader(new JsonMapper());
        SceneAnalysisStage stage = new SceneAnalysisStage(storageService, screenAnalyzer, speakerTracker,
                transcriptReader, new JsonMapper());

        writeSource(storageService, videoId);
        writeFaces(storageService, videoId);
        writeTranscript(storageService, videoId);

        stage.run(new Video(UUID.fromString(videoId), "abc123", "https://example.com", "Test", 8, null,
                PipelineStatus.SCENE_ANALYSIS, Instant.now(), null));
        storageService.commit(videoId, Artifacts.SCENE);

        Path sceneFile = storageService.resolve(videoId, Artifacts.SCENE);
        assertThat(sceneFile).exists();
        Scene scene = new JsonMapper().readValue(Files.readString(sceneFile), Scene.class);
        assertThat(scene.hasCode()).isTrue();
        assertThat(scene.screenRegion()).isNotNull();
        assertThat(scene.activeSpeaker()).hasSize(1);
    }

    private void writeSource(StorageService storageService, String videoId) throws Exception {
        Path target = storageService.prepareFor(videoId, Artifacts.SOURCE);
        createTestVideo(target, 8);
    }

    private void writeFaces(StorageService storageService, String videoId) throws Exception {
        Path target = storageService.temporaryFor(videoId, Artifacts.FACES);
        Files.writeString(target, new JsonMapper().writeValueAsString(new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(new FaceSample(1.0, 960, 540, 200, 200, 2.0),
                        new FaceSample(5.0, 960, 540, 200, 200, 1.0)))))));
        storageService.commit(videoId, Artifacts.FACES);
    }

    private void writeTranscript(StorageService storageService, String videoId) throws Exception {
        Path target = storageService.temporaryFor(videoId, Artifacts.TRANSCRIPT);
        Files.writeString(target, """
                {"segments":[{"words":[
                {"word":"hello","start":1.0,"end":1.5},
                {"word":"world","start":1.5,"end":2.0}
                ]}]}""");
        storageService.commit(videoId, Artifacts.TRANSCRIPT);
    }

    private void createTestVideo(Path target, int durationSeconds) throws Exception {
        String text = "public class Hello {\\n  public static void main(String[] args) {\\n    System.out.println(\"hi\");\\n  }\\n}";
        processRunner.run("ffmpeg", List.of("ffmpeg",
                "-y",
                "-hide_banner",
                "-nostats",
                "-f", "lavfi",
                "-i", "color=c=white:s=1280x720:d=%d".formatted(durationSeconds),
                "-f", "lavfi",
                "-i", "anullsrc=channel_layout=mono:sample_rate=48000",
                "-t", String.valueOf(durationSeconds),
                "-vf", ("drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf:" +
                        "text='%s':x=140:y=80:fontsize=28:fontcolor=black:line_spacing=10," +
                        "format=yuv420p").formatted(text),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-c:a", "aac",
                "-shortest",
                target.toString()), java.time.Duration.ofMinutes(2));
    }

    private KirinukiPipelineProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
