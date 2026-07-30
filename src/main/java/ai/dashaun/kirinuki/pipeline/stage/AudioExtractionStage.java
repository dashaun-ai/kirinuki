package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.media.AudioFeatureExtractor;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
class AudioExtractionStage implements PipelineStage {

    private final StorageService storageService;
    private final AudioFeatureExtractor audioFeatureExtractor;

    AudioExtractionStage(StorageService storageService, AudioFeatureExtractor audioFeatureExtractor) {
        this.storageService = storageService;
        this.audioFeatureExtractor = audioFeatureExtractor;
    }

    @Override
    public String artifact() {
        return Artifacts.AUDIO_FEATURES;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.FEATURE_EXTRACTION;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        audioFeatureExtractor.extract(storageService.resolve(videoId, Artifacts.AUDIO),
                storageService.temporaryFor(videoId, Artifacts.AUDIO_FEATURES));
    }
}
