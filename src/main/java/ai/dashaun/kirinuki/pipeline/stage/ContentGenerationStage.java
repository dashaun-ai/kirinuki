package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.content.ContentGenerator;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.ContentGeneratedEvent;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
class ContentGenerationStage implements PipelineStage {

    private final StorageService storageService;
    private final ContentGenerator contentGenerator;
    private final ApplicationEventPublisher eventPublisher;

    ContentGenerationStage(StorageService storageService, ContentGenerator contentGenerator,
            ApplicationEventPublisher eventPublisher) {
        this.storageService = storageService;
        this.contentGenerator = contentGenerator;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String artifact() {
        return Artifacts.CONTENT;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.CONTENT_GENERATION;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        contentGenerator.generate(storageService.resolve(videoId, Artifacts.SCORED),
                storageService.temporaryFor(videoId, Artifacts.CONTENT), video.getTitle());
    }

    @Override
    public void afterCommit(Video video) {
        eventPublisher.publishEvent(new ContentGeneratedEvent(video.getId(),
                storageService.resolve(video.getId().toString(), Artifacts.CONTENT)));
    }
}
