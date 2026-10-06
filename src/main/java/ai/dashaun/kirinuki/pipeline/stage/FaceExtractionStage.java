package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.vision.FaceExtractor;

@Component
@ConditionalOnProperty(prefix = "kirinuki.pipeline.visual", name = "enabled", havingValue = "true")
class FaceExtractionStage implements PipelineStage {

    private final StorageService storageService;
    private final FaceExtractor faceExtractor;

    FaceExtractionStage(StorageService storageService, FaceExtractor faceExtractor) {
        this.storageService = storageService;
        this.faceExtractor = faceExtractor;
    }

    @Override
    public String artifact() {
        return Artifacts.FACES;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.FEATURE_EXTRACTION;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        faceExtractor.extract(storageService.resolve(videoId, Artifacts.SOURCE),
                storageService.temporaryFor(videoId, Artifacts.FACES));
    }
}
