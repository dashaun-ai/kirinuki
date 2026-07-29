package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.media.SceneExtractor;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
class SceneExtractionStage implements PipelineStage {

    private final StorageService storageService;
    private final SceneExtractor sceneExtractor;

    SceneExtractionStage(StorageService storageService, SceneExtractor sceneExtractor) {
        this.storageService = storageService;
        this.sceneExtractor = sceneExtractor;
    }

    @Override
    public String artifact() {
        return Artifacts.SCENES;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.FEATURE_EXTRACTION;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        sceneExtractor.extract(storageService.resolve(videoId, Artifacts.SOURCE),
                storageService.temporaryFor(videoId, Artifacts.SCENES));
    }
}
