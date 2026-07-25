package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.render.ClipRenderer;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
class RenderClipsStage implements PipelineStage {

    private final StorageService storageService;
    private final ClipRenderer clipRenderer;

    RenderClipsStage(StorageService storageService, ClipRenderer clipRenderer) {
        this.storageService = storageService;
        this.clipRenderer = clipRenderer;
    }

    @Override
    public String artifact() {
        return Artifacts.CLIPS;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.CLIP_RENDERING;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        clipRenderer.render(
                storageService.resolve(videoId, Artifacts.SOURCE),
                storageService.resolve(videoId, Artifacts.TRANSCRIPT),
                storageService.resolve(videoId, Artifacts.SCORED),
                storageService.resolve(videoId, Artifacts.CLIP_DIRECTORY),
                storageService.temporaryFor(videoId, Artifacts.CLIPS));
    }
}
