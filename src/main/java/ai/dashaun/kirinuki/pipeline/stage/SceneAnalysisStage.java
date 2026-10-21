package ai.dashaun.kirinuki.pipeline.stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.vision.FaceTrack;
import ai.dashaun.kirinuki.vision.FaceTracks;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.vision.SceneSpeaker;
import ai.dashaun.kirinuki.vision.ScreenAnalysis;
import ai.dashaun.kirinuki.vision.ScreenAnalyzer;
import ai.dashaun.kirinuki.vision.SpeakerActivity;
import ai.dashaun.kirinuki.vision.SpeakerTracker;
import tools.jackson.databind.ObjectMapper;

@Component
class SceneAnalysisStage implements PipelineStage {

    private final StorageService storageService;
    private final ScreenAnalyzer screenAnalyzer;
    private final SpeakerTracker speakerTracker;
    private final TranscriptReader transcriptReader;
    private final ObjectMapper objectMapper;

    SceneAnalysisStage(StorageService storageService, ScreenAnalyzer screenAnalyzer, SpeakerTracker speakerTracker,
            TranscriptReader transcriptReader, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.screenAnalyzer = screenAnalyzer;
        this.speakerTracker = speakerTracker;
        this.transcriptReader = transcriptReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public String artifact() {
        return Artifacts.SCENE;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.SCENE_ANALYSIS;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        Path source = storageService.resolve(videoId, Artifacts.SOURCE);
        Path facesFile = storageService.resolve(videoId, Artifacts.FACES);
        Path transcriptFile = storageService.resolve(videoId, Artifacts.TRANSCRIPT);
        Path screenFile = storageService.temporaryFor(videoId, Artifacts.SCREEN_ANALYSIS);
        Path target = storageService.temporaryFor(videoId, Artifacts.SCENE);

        screenAnalyzer.analyze(source, screenFile);
        FaceTracks faces = readFaces(facesFile);
        List<Word> words = transcriptReader.readWords(transcriptFile);
        List<SpeakerActivity> speakers = speakerTracker.track(faces, words, 0.0, video.getDurationSeconds());
        ScreenAnalysis screen = readScreen(screenFile);
        Scene scene = new Scene(buildSpeakers(faces), speakers, screen.screenRegion(), screen.screenChanges(),
                screen.hasCode(), faces);
        write(scene, target);
    }

    private FaceTracks readFaces(Path facesFile) {
        try {
            if (!Files.exists(facesFile)) {
                return FaceTracks.empty();
            }
            return objectMapper.readValue(Files.readString(facesFile), FaceTracks.class);
        } catch (IOException exception) {
            throw new KirinukiException("Could not read faces from " + facesFile, exception);
        }
    }

    private ScreenAnalysis readScreen(Path screenFile) {
        try {
            return objectMapper.readValue(Files.readString(screenFile), ScreenAnalysis.class);
        } catch (IOException exception) {
            throw new KirinukiException("Could not read screen analysis from " + screenFile, exception);
        }
    }

    private List<SceneSpeaker> buildSpeakers(FaceTracks faces) {
        return faces.tracks().stream()
                .map(track -> new SceneSpeaker(String.valueOf(track.id()), "track-" + track.id()))
                .toList();
    }

    private void write(Scene scene, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(scene));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write scene to " + target, exception);
        }
    }
}
