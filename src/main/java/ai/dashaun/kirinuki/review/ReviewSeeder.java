package ai.dashaun.kirinuki.review;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.pipeline.ContentGeneratedEvent;

@Component
public class ReviewSeeder {

    private final ReviewService reviewService;

    public ReviewSeeder(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @EventListener
    public void onContentGenerated(ContentGeneratedEvent event) {
        reviewService.seedFromContent(event.videoId(), event.contentPath());
    }
}
