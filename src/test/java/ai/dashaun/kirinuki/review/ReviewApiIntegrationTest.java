package ai.dashaun.kirinuki.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ai.dashaun.kirinuki.AbstractIntegrationTest;
import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.ContentGenerationClient;
import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineOrchestrator;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoResponse;
import tools.jackson.databind.ObjectMapper;

class ReviewApiIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private ContentGenerationClient contentClient;

    @MockitoBean
    private PipelineOrchestrator pipelineOrchestrator;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID videoId;

    @BeforeEach
    void seedArtifacts() throws IOException {
        videoId = videoRepository.save(new Video(UUID.randomUUID(), "dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ", "Spring Boot 4 in anger", 840, "your_javaguy",
                PipelineStatus.READY_FOR_REVIEW, Instant.now(), null)).getId();
        writeArtifact(Artifacts.SCORED, List.of(
                scored(0, 88, "the first moment worth clipping"),
                scored(1, 71, "the second moment worth clipping")));
        writeArtifact(Artifacts.CONTENT, List.of(content(1), content(2)));
    }

    @Test
    void should_seed_a_pending_review_row_per_clip_when_the_review_is_first_opened() {
        List<ClipReviewResponse> review = review();

        assertThat(review).extracting(ClipReviewResponse::clipIndex).containsExactly(1, 2);
        assertThat(review).allMatch(clip -> clip.status() == ReviewStatus.PENDING);
        assertThat(clipReviewRepository.findByVideoIdOrderByClipIndex(videoId)).hasSize(2);
    }

    @Test
    void should_join_the_score_and_transcript_onto_each_clip_when_the_review_is_opened() {
        List<ClipReviewResponse> review = review();

        assertThat(review.getFirst().overallScore()).isEqualTo(88);
        assertThat(review.getFirst().transcript()).isEqualTo("the first moment worth clipping");
        assertThat(review.getFirst().score().hook()).isEqualTo(9);
    }

    @Test
    void should_point_each_clip_at_its_download_url_when_the_review_is_opened() {
        assertThat(review().getFirst().downloadUrl()).isEqualTo("/videos/" + videoId + "/clips/1");
    }

    @Test
    void should_not_seed_twice_when_the_review_is_opened_again() {
        review();
        review();

        assertThat(clipReviewRepository.findByVideoIdOrderByClipIndex(videoId)).hasSize(2);
    }

    @Test
    void should_persist_the_edited_summary_when_a_clip_is_patched() {
        review();

        ClipReviewResponse response = client.patch().uri("/videos/{videoId}/review/{clipIndex}", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"version\": 0, \"summary\": \"a sharper summary\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClipReviewResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.summary()).isEqualTo("a sharper summary");
        assertThat(review().getFirst().summary()).isEqualTo("a sharper summary");
    }

    @Test
    void should_leave_the_untouched_fields_alone_when_a_clip_is_patched() {
        review();

        ClipReviewResponse response = client.patch().uri("/videos/{videoId}/review/{clipIndex}", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"version\": 0, \"summary\": \"a sharper summary\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClipReviewResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.keywords()).containsExactly("spring boot");
        assertThat(response.platforms()).extracting(PlatformVariant::platform).containsExactly("TikTok");
    }

    @Test
    void should_answer_conflict_when_a_clip_is_patched_from_a_stale_version() {
        review();
        client.patch().uri("/videos/{videoId}/review/{clipIndex}", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"version\": 0, \"summary\": \"the first edit wins\"}")
                .exchange()
                .expectStatus().isOk();

        ProblemDetail problem = client.patch().uri("/videos/{videoId}/review/{clipIndex}", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"version\": 0, \"summary\": \"a second edit from a stale form\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Clip changed since you loaded it");
        assertThat(review().getFirst().summary()).isEqualTo("the first edit wins");
    }

    @Test
    void should_advance_the_version_when_a_clip_is_patched() {
        review();

        ClipReviewResponse response = client.patch().uri("/videos/{videoId}/review/{clipIndex}", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"version\": 0, \"summary\": \"a sharper summary\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClipReviewResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    void should_record_the_decision_when_a_clip_is_approved() {
        review();

        client.post().uri("/videos/{videoId}/review/{clipIndex}/approve", videoId, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClipReviewResponse.class)
                .returnResult();

        assertThat(clipReviewRepository.findByVideoIdAndClipIndex(videoId, 1))
                .get()
                .extracting(ClipReview::getStatus)
                .isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void should_record_the_decision_when_a_clip_is_rejected() {
        review();

        client.post().uri("/videos/{videoId}/review/{clipIndex}/reject", videoId, 2)
                .exchange()
                .expectStatus().isOk();

        assertThat(clipReviewRepository.findByVideoIdAndClipIndex(videoId, 2))
                .get()
                .extracting(ClipReview::getStatus)
                .isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void should_answer_not_found_when_deciding_on_a_clip_that_does_not_exist() {
        review();

        ProblemDetail problem = client.post().uri("/videos/{videoId}/review/{clipIndex}/approve", videoId, 9)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Clip not found");
    }

    @Test
    void should_replace_the_field_from_the_model_when_a_clip_field_is_regenerated() {
        review();
        when(contentClient.regenerateText(anyString(), anyString(), anyString())).thenReturn("a regenerated summary");

        ClipReviewResponse response = client.post()
                .uri("/videos/{videoId}/review/{clipIndex}/regenerate", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"field\": \"SUMMARY\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClipReviewResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.summary()).isEqualTo("a regenerated summary");
        verify(contentClient).regenerateText("Spring Boot 4 in anger", "the first moment worth clipping",
                "the summary");
    }

    @Test
    void should_answer_bad_request_when_a_platform_field_is_regenerated_without_a_platform() {
        review();

        ProblemDetail problem = client.post()
                .uri("/videos/{videoId}/review/{clipIndex}/regenerate", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"field\": \"CAPTION\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Invalid regeneration request");
    }

    @Test
    void should_answer_bad_request_when_the_regenerated_field_is_missing() {
        review();

        client.post().uri("/videos/{videoId}/review/{clipIndex}/regenerate", videoId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void should_move_the_video_to_ready_to_publish_when_the_whole_review_is_approved() {
        review();

        VideoResponse response = client.post().uri("/videos/{videoId}/review/approve", videoId)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(VideoResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response.status()).isEqualTo(PipelineStatus.READY_TO_PUBLISH);
        assertThat(videoRepository.findById(videoId))
                .get()
                .extracting(Video::getStatus)
                .isEqualTo(PipelineStatus.READY_TO_PUBLISH);
        verify(pipelineOrchestrator).startAsync(videoId);
    }

    @Test
    void should_answer_conflict_when_the_review_is_approved_before_the_clips_are_ready() {
        Video video = videoRepository.findById(videoId).orElseThrow();
        video.setStatus(PipelineStatus.CLIP_RENDERING);
        videoRepository.save(video);
        review();

        ProblemDetail problem = client.post().uri("/videos/{videoId}/review/approve", videoId)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Video is not ready for review");
    }

    @Test
    void should_answer_not_found_when_the_content_artifact_is_missing() throws IOException {
        Files.delete(storageService.resolve(videoId.toString(), Artifacts.CONTENT));

        ProblemDetail problem = client.get().uri("/videos/{videoId}/review", videoId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem.getTitle()).isEqualTo("Artifact not found");
    }

    private List<ClipReviewResponse> review() {
        return client.get().uri("/videos/{videoId}/review", videoId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<ClipReviewResponse>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    private ClipContent content(int clipIndex) {
        return new ClipContent(clipIndex, "what clip %d teaches".formatted(clipIndex), List.of("spring boot"),
                List.of("springboot"),
                List.of(new PlatformVariant("TikTok", "tiktok title", "tiktok caption", List.of("java"), "")));
    }

    private ScoredCandidate scored(int id, int overallScore, String text) {
        return new ScoredCandidate(new Candidate(id, 10.0 + id * 100, 40.0 + id * 100, id * 10, id * 10 + 9, text),
                new CandidateScore(9, 8, 7, 9, List.of("TikTok"), "strong hook"), overallScore);
    }

    private void writeArtifact(String artifact, Object content) throws IOException {
        Path target = storageService.prepareFor(videoId.toString(), artifact);
        Files.writeString(target, objectMapper.writeValueAsString(content));
    }
}
