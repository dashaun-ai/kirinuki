package ai.dashaun.kirinuki.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import ai.dashaun.kirinuki.common.ClipReviewNotFoundException;
import ai.dashaun.kirinuki.common.InvalidRegenerationException;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.ContentGenerationClient;
import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.video.VideoResponse;
import ai.dashaun.kirinuki.video.VideoService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReviewServiceTest {

    private static final UUID VIDEO_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String VIDEO_TITLE = "Spring Boot 4 in anger";

    private final ClipReviewRepository clipReviewRepository = mock(ClipReviewRepository.class);
    private final VideoService videoService = mock(VideoService.class);
    private final ContentGenerationClient contentClient = mock(ContentGenerationClient.class);
    private final ObjectMapper objectMapper = new JsonMapper();

    private final ReviewService reviewService =
            new ReviewService(clipReviewRepository, videoService, objectMapper, contentClient);

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws IOException {
        when(clipReviewRepository.existsByVideoId(VIDEO_ID)).thenReturn(true);
        when(videoService.findById(VIDEO_ID)).thenReturn(new VideoResponse(VIDEO_ID, "dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ", VIDEO_TITLE, 840, "your_javaguy", PipelineStatus.READY_FOR_REVIEW,
                Instant.now(), null));
        writeScored(scored(1, 82, "the moment a viewer would clip"));
    }

    @Test
    void should_expose_the_transcript_and_score_of_the_matching_scored_candidate() {
        when(clipReviewRepository.findByVideoIdOrderByClipIndex(VIDEO_ID)).thenReturn(List.of(row(1, content())));

        ClipReviewResponse response = reviewService.review(VIDEO_ID).getFirst();

        assertThat(response.transcript()).isEqualTo("the moment a viewer would clip");
        assertThat(response.overallScore()).isEqualTo(82);
    }

    @Test
    void should_point_at_the_clip_download_when_a_review_is_listed() {
        when(clipReviewRepository.findByVideoIdOrderByClipIndex(VIDEO_ID)).thenReturn(List.of(row(1, content())));

        ClipReviewResponse response = reviewService.review(VIDEO_ID).getFirst();

        assertThat(response.downloadUrl()).isEqualTo("/videos/" + VIDEO_ID + "/clips/1");
    }

    @Test
    void should_report_no_score_when_the_clip_has_no_scored_candidate() {
        when(clipReviewRepository.findByVideoIdOrderByClipIndex(VIDEO_ID)).thenReturn(List.of(row(7, content())));

        ClipReviewResponse response = reviewService.review(VIDEO_ID).getFirst();

        assertThat(response.overallScore()).isZero();
        assertThat(response.transcript()).isNull();
        assertThat(response.score()).isNull();
    }

    @Test
    void should_seed_one_pending_row_per_clip_when_the_video_has_not_been_reviewed_yet() throws IOException {
        when(clipReviewRepository.existsByVideoId(VIDEO_ID)).thenReturn(false);
        writeContentArtifact(List.of(content(), new ClipContent(2, "second summary", List.of("k"), List.of("t"),
                List.of())));
        when(clipReviewRepository.findByVideoIdOrderByClipIndex(VIDEO_ID)).thenReturn(List.of());

        reviewService.review(VIDEO_ID);

        ArgumentCaptor<List<ClipReview>> seeded = ArgumentCaptor.captor();
        verify(clipReviewRepository).saveAll(seeded.capture());
        assertThat(seeded.getValue()).extracting(ClipReview::getClipIndex).containsExactly(1, 2);
        assertThat(seeded.getValue()).allMatch(row -> row.getStatus() == ReviewStatus.PENDING);
    }

    @Test
    void should_not_seed_again_when_the_video_already_has_review_rows() {
        when(clipReviewRepository.findByVideoIdOrderByClipIndex(VIDEO_ID)).thenReturn(List.of(row(1, content())));

        reviewService.review(VIDEO_ID);

        verify(clipReviewRepository, never()).saveAll(anyList());
    }

    @Test
    void should_store_the_decision_when_a_clip_is_approved() {
        ClipReview row = row(1, content());
        stubRow(row);

        ClipReviewResponse response = reviewService.decide(VIDEO_ID, 1, ReviewStatus.APPROVED);

        assertThat(response.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(row.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        verify(clipReviewRepository).save(row);
    }

    @Test
    void should_fail_when_deciding_on_a_clip_that_does_not_exist() {
        when(clipReviewRepository.findByVideoIdAndClipIndex(VIDEO_ID, 9)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ClipReviewNotFoundException.class)
                .isThrownBy(() -> reviewService.decide(VIDEO_ID, 9, ReviewStatus.APPROVED));
    }

    @Test
    void should_replace_only_the_edited_field_when_the_rest_of_the_request_is_empty() {
        stubRow(row(1, content()));

        ClipReviewResponse response = reviewService.edit(VIDEO_ID, 1,
                new EditClipContentRequest("a sharper summary", null, null, null));

        assertThat(response.summary()).isEqualTo("a sharper summary");
        assertThat(response.keywords()).containsExactly("spring boot", "virtual threads");
        assertThat(response.tags()).containsExactly("springboot", "java");
        assertThat(response.platforms()).hasSize(2);
    }

    @Test
    void should_keep_every_field_when_the_edit_request_is_empty() {
        stubRow(row(1, content()));

        ClipReviewResponse response = reviewService.edit(VIDEO_ID, 1,
                new EditClipContentRequest(null, null, null, null));

        assertThat(response.summary()).isEqualTo("what this video teaches");
    }

    @Test
    void should_replace_the_platform_list_when_the_edit_request_carries_one() {
        stubRow(row(1, content()));

        ClipReviewResponse response = reviewService.edit(VIDEO_ID, 1, new EditClipContentRequest(null, null, null,
                List.of(new PlatformVariant("X", "x title", "x caption", List.of("java"), ""))));

        assertThat(response.platforms()).extracting(PlatformVariant::platform).containsExactly("X");
    }

    @Test
    void should_stamp_the_update_time_when_a_clip_is_edited() {
        ClipReview row = row(1, content());
        Instant before = row.getUpdatedAt();
        stubRow(row);

        reviewService.edit(VIDEO_ID, 1, new EditClipContentRequest("edited", null, null, null));

        assertThat(row.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void should_regenerate_only_the_summary_when_the_summary_is_asked_for() {
        stubRow(row(1, content()));
        when(contentClient.regenerateText(eq(VIDEO_TITLE), anyString(), eq("the summary")))
                .thenReturn("a regenerated summary");

        ClipReviewResponse response =
                reviewService.regenerate(VIDEO_ID, 1, new RegenerateFieldRequest(ContentField.SUMMARY, null));

        assertThat(response.summary()).isEqualTo("a regenerated summary");
        assertThat(response.keywords()).containsExactly("spring boot", "virtual threads");
    }

    @Test
    void should_send_the_scored_transcript_to_the_model_when_a_field_is_regenerated() {
        stubRow(row(1, content()));

        reviewService.regenerate(VIDEO_ID, 1, new RegenerateFieldRequest(ContentField.SUMMARY, null));

        verify(contentClient).regenerateText(VIDEO_TITLE, "the moment a viewer would clip", "the summary");
    }

    @Test
    void should_regenerate_only_the_keywords_when_the_keywords_are_asked_for() {
        stubRow(row(1, content()));
        when(contentClient.regenerateList(eq(VIDEO_TITLE), anyString(), anyString()))
                .thenReturn(List.of("fresh", "keywords"));

        ClipReviewResponse response =
                reviewService.regenerate(VIDEO_ID, 1, new RegenerateFieldRequest(ContentField.KEYWORDS, null));

        assertThat(response.keywords()).containsExactly("fresh", "keywords");
        assertThat(response.tags()).containsExactly("springboot", "java");
    }

    @Test
    void should_regenerate_only_the_named_platform_when_a_platform_field_is_asked_for() {
        stubRow(row(1, content()));
        when(contentClient.regenerateText(eq(VIDEO_TITLE), anyString(), anyString())).thenReturn("a new caption");

        ClipReviewResponse response = reviewService.regenerate(VIDEO_ID, 1,
                new RegenerateFieldRequest(ContentField.CAPTION, "TikTok"));

        assertThat(response.platforms()).containsExactly(
                new PlatformVariant("TikTok", "tiktok title", "a new caption", List.of("java"), ""),
                new PlatformVariant("LinkedIn", "linkedin title", "linkedin caption", List.of("spring"), "follow"));
    }

    @Test
    void should_match_the_platform_regardless_of_case() {
        stubRow(row(1, content()));
        when(contentClient.regenerateText(eq(VIDEO_TITLE), anyString(), anyString())).thenReturn("a new title");

        ClipReviewResponse response = reviewService.regenerate(VIDEO_ID, 1,
                new RegenerateFieldRequest(ContentField.TITLE, "tiktok"));

        assertThat(response.platforms().getFirst().title()).isEqualTo("a new title");
    }

    @Test
    void should_regenerate_the_platform_hashtags_as_a_list() {
        stubRow(row(1, content()));
        when(contentClient.regenerateList(eq(VIDEO_TITLE), anyString(), anyString()))
                .thenReturn(List.of("springboot", "java25"));

        ClipReviewResponse response = reviewService.regenerate(VIDEO_ID, 1,
                new RegenerateFieldRequest(ContentField.HASHTAGS, "TikTok"));

        assertThat(response.platforms().getFirst().hashtags()).containsExactly("springboot", "java25");
    }

    @Test
    void should_fail_when_a_platform_field_is_regenerated_without_a_platform() {
        stubRow(row(1, content()));

        assertThatExceptionOfType(InvalidRegenerationException.class)
                .isThrownBy(() -> reviewService.regenerate(VIDEO_ID, 1,
                        new RegenerateFieldRequest(ContentField.CAPTION, null)))
                .withMessageContaining("requires a platform");
        verifyNoInteractions(contentClient);
    }

    @Test
    void should_fail_when_a_platform_field_is_regenerated_with_a_blank_platform() {
        stubRow(row(1, content()));

        assertThatExceptionOfType(InvalidRegenerationException.class)
                .isThrownBy(() -> reviewService.regenerate(VIDEO_ID, 1,
                        new RegenerateFieldRequest(ContentField.CAPTION, "  ")));
    }

    @Test
    void should_fail_when_the_platform_is_not_one_of_the_generated_variants() {
        stubRow(row(1, content()));

        assertThatExceptionOfType(InvalidRegenerationException.class)
                .isThrownBy(() -> reviewService.regenerate(VIDEO_ID, 1,
                        new RegenerateFieldRequest(ContentField.CAPTION, "Threads")))
                .withMessageContaining("not found for this clip");
        verifyNoInteractions(contentClient);
    }

    @Test
    void should_regenerate_from_an_empty_transcript_when_the_clip_has_no_scored_candidate() {
        stubRow(row(7, content()));
        when(clipReviewRepository.findByVideoIdAndClipIndex(VIDEO_ID, 7)).thenReturn(Optional.of(row(7, content())));

        reviewService.regenerate(VIDEO_ID, 7, new RegenerateFieldRequest(ContentField.SUMMARY, null));

        verify(contentClient).regenerateText(VIDEO_TITLE, "", "the summary");
    }

    @Test
    void should_delegate_to_the_video_service_when_the_whole_review_is_approved() {
        reviewService.approve(VIDEO_ID);

        verify(videoService).approveReview(VIDEO_ID);
    }

    private void stubRow(ClipReview row) {
        when(clipReviewRepository.findByVideoIdAndClipIndex(VIDEO_ID, row.getClipIndex()))
                .thenReturn(Optional.of(row));
    }

    private ClipReview row(int clipIndex, ClipContent content) {
        return new ClipReview(UUID.randomUUID(), VIDEO_ID, clipIndex, ReviewStatus.PENDING,
                objectMapper.writeValueAsString(content), Instant.now(), Instant.now());
    }

    private ClipContent content() {
        return new ClipContent(1, "what this video teaches", List.of("spring boot", "virtual threads"),
                List.of("springboot", "java"),
                List.of(new PlatformVariant("TikTok", "tiktok title", "tiktok caption", List.of("java"), ""),
                        new PlatformVariant("LinkedIn", "linkedin title", "linkedin caption", List.of("spring"),
                                "follow")));
    }

    private ScoredCandidate scored(int id, int overallScore, String text) {
        return new ScoredCandidate(new Candidate(id, 10.0, 40.0, 0, 9, text),
                new CandidateScore(9, 8, 7, 5, 9, List.of("TikTok"), "strong hook"), overallScore);
    }

    private void writeScored(ScoredCandidate... scored) throws IOException {
        Path path = Files.writeString(directory.resolve(Artifacts.SCORED),
                objectMapper.writeValueAsString(List.of(scored)));
        when(videoService.artifactPath(VIDEO_ID, Artifacts.SCORED)).thenReturn(path);
    }

    private void writeContentArtifact(List<ClipContent> content) throws IOException {
        Path path = Files.writeString(directory.resolve(Artifacts.CONTENT), objectMapper.writeValueAsString(content));
        when(videoService.artifactPath(VIDEO_ID, Artifacts.CONTENT)).thenReturn(path);
    }
}
