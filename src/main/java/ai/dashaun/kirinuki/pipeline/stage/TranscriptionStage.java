package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.core.annotation.Order;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.media.WhisperClient;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
@Order(3)
class TranscriptionStage implements PipelineStage {
    private final StorageService storageService;
    private final WhisperClient whisperClient;

    TranscriptionStage(StorageService storageService, WhisperClient whisperClient) {
        this.storageService = storageService;
        this.whisperClient = whisperClient;
    }

    @Override
    public String artifact() {
        return Artifacts.TRANSCRIPT;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.FEATURE_EXTRACTION;
    }

    @Override
    public PipelineStatus completedStatus() {
        return PipelineStatus.WAITING_FOR_FEATURES;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        whisperClient.transcribe(storageService.resolve(videoId, Artifacts.AUDIO),
                storageService.temporaryFor(videoId, Artifacts.TRANSCRIPT));
    }
}
