package ai.dashaun.kirinuki.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import ai.dashaun.kirinuki.AbstractIntegrationTest;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.video.Video;

class ClipReviewRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Test
    void should_return_the_clips_in_index_order_when_they_were_saved_out_of_order() {
        UUID videoId = persistedVideo();
        clipReviewRepository.saveAll(List.of(review(videoId, 3), review(videoId, 1), review(videoId, 2)));

        List<ClipReview> reviews = clipReviewRepository.findByVideoIdOrderByClipIndex(videoId);

        assertThat(reviews).extracting(ClipReview::getClipIndex).containsExactly(1, 2, 3);
    }

    @Test
    void should_find_a_single_clip_by_its_video_and_index() {
        UUID videoId = persistedVideo();
        clipReviewRepository.saveAll(List.of(review(videoId, 1), review(videoId, 2)));

        assertThat(clipReviewRepository.findByVideoIdAndClipIndex(videoId, 2))
                .get()
                .extracting(ClipReview::getClipIndex)
                .isEqualTo(2);
    }

    @Test
    void should_find_nothing_when_the_clip_index_is_unknown() {
        UUID videoId = persistedVideo();
        clipReviewRepository.save(review(videoId, 1));

        assertThat(clipReviewRepository.findByVideoIdAndClipIndex(videoId, 9)).isEmpty();
    }

    @Test
    void should_report_no_reviews_when_the_video_has_not_been_seeded() {
        assertThat(clipReviewRepository.existsByVideoId(persistedVideo())).isFalse();
    }

    @Test
    void should_report_reviews_when_the_video_has_been_seeded() {
        UUID videoId = persistedVideo();
        clipReviewRepository.save(review(videoId, 1));

        assertThat(clipReviewRepository.existsByVideoId(videoId)).isTrue();
    }

    @Test
    void should_keep_the_reviews_of_other_videos_out_of_the_result() {
        UUID videoId = persistedVideo();
        UUID otherVideoId = persistedVideo();
        clipReviewRepository.saveAll(List.of(review(videoId, 1), review(otherVideoId, 1), review(otherVideoId, 2)));

        assertThat(clipReviewRepository.findByVideoIdOrderByClipIndex(videoId)).hasSize(1);
    }

    @Test
    void should_reject_a_second_review_row_for_the_same_clip() {
        UUID videoId = persistedVideo();
        clipReviewRepository.save(review(videoId, 1));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> clipReviewRepository.save(review(videoId, 1)));
    }

    @Test
    void should_reject_a_review_row_for_a_video_that_does_not_exist() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> clipReviewRepository.save(review(UUID.randomUUID(), 1)));
    }

    @Test
    void should_round_trip_the_content_json_and_the_status() {
        UUID videoId = persistedVideo();
        ClipReview saved = clipReviewRepository.save(review(videoId, 1));

        ClipReview reloaded = clipReviewRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getContent()).isEqualTo("{\"clipIndex\":1}");
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    private UUID persistedVideo() {
        UUID videoId = UUID.randomUUID();
        videoRepository.save(new Video(videoId, videoId.toString().substring(0, 11),
                "https://youtu.be/" + videoId.toString().substring(0, 11), "A talk", 840, "your_javaguy",
                PipelineStatus.READY_FOR_REVIEW, Instant.now(), null));
        return videoId;
    }

    private ClipReview review(UUID videoId, int clipIndex) {
        return new ClipReview(UUID.randomUUID(), videoId, clipIndex, ReviewStatus.PENDING, "{\"clipIndex\":1}",
                Instant.now(), Instant.now());
    }
}
