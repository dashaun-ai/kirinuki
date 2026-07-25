package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
class MediaPreparationStage implements PipelineStage {

    private final StorageService storageService;
    private final FfmpegClient ffmpegClient;

    MediaPreparationStage(StorageService storageService, FfmpegClient ffmpegClient) {
        this.storageService = storageService;
        this.ffmpegClient = ffmpegClient;
    }

    @Override
    public String artifact() {
        return Artifacts.AUDIO;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.MEDIA_PREPARATION;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        ffmpegClient.extractAudio(storageService.resolve(videoId, Artifacts.SOURCE),
                storageService.temporaryFor(videoId, Artifacts.AUDIO));
    }
}
