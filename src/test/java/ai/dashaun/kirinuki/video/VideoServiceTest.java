package ai.dashaun.kirinuki.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import ai.dashaun.kirinuki.common.ArtifactNotFoundException;
import ai.dashaun.kirinuki.common.DuplicateVideoException;
import ai.dashaun.kirinuki.common.InvalidVideoUrlException;
import ai.dashaun.kirinuki.common.ReviewNotReadyException;
import ai.dashaun.kirinuki.common.VideoNotFoundException;
import ai.dashaun.kirinuki.common.VideoNotResumableException;
import ai.dashaun.kirinuki.pipeline.PipelineOrchestrator;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;

class VideoServiceTest {

    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final YtDlpClient ytDlpClient = mock(YtDlpClient.class);
    private final StorageService storageService = mock(StorageService.class);
    private final PipelineOrchestrator pipelineOrchestrator = mock(PipelineOrchestrator.class);

    private final VideoService videoService =
            new VideoService(videoRepository, ytDlpClient, storageService, pipelineOrchestrator);

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s, dQw4w9WgXcQ",
            "https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc, dQw4w9WgXcQ" })
    void should_read_the_video_id_from_every_supported_url_shape(String url, String youtubeId) {
        stubSuccessfulIngest(youtubeId);

        videoService.ingest(url);

        verify(videoRepository).existsByYoutubeId(youtubeId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://vimeo.com/123456789",
            "https://www.youtube.com/watch?v=tooshort",
            "https://www.youtube.com/@your_javaguy",
            "not a url at all",
            "" })
    void should_reject_a_url_that_is_not_a_youtube_video(String url) {
        assertThatExceptionOfType(InvalidVideoUrlException.class).isThrownBy(() -> videoService.ingest(url));
        verifyNoInteractions(ytDlpClient);
    }

    @Test
    void should_fail_when_the_video_was_already_ingested() {
        when(videoRepository.existsByYoutubeId("dQw4w9WgXcQ")).thenReturn(true);

        assertThatExceptionOfType(DuplicateVideoException.class).isThrownBy(() -> videoService.ingest(URL));
        verifyNoInteractions(ytDlpClient);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void should_store_the_metadata_from_yt_dlp_when_a_video_is_ingested() {
        stubSuccessfulIngest("dQw4w9WgXcQ");

        VideoResponse response = videoService.ingest(URL);

        assertThat(response.youtubeId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(response.title()).isEqualTo("Spring Boot 4 in anger");
        assertThat(response.durationSeconds()).isEqualTo(840);
        assertThat(response.uploader()).isEqualTo("your_javaguy");
        assertThat(response.sourceUrl()).isEqualTo(URL);
    }

    @Test
    void should_start_at_downloading_when_a_video_is_ingested() {
        stubSuccessfulIngest("dQw4w9WgXcQ");

        VideoResponse response = videoService.ingest(URL);

        assertThat(response.status()).isEqualTo(PipelineStatus.DOWNLOADING);
        assertThat(response.lastError()).isNull();
    }

    @Test
    void should_hand_the_video_to_the_pipeline_when_it_is_ingested() {
        stubSuccessfulIngest("dQw4w9WgXcQ");

        VideoResponse response = videoService.ingest(URL);

        verify(pipelineOrchestrator).startAsync(response.id());
    }

    @Test
    void should_persist_the_video_before_the_pipeline_starts() {
        stubSuccessfulIngest("dQw4w9WgXcQ");

        videoService.ingest(URL);

        ArgumentCaptor<Video> saved = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PipelineStatus.DOWNLOADING);
    }

    @Test
    void should_fail_when_looking_up_a_video_that_does_not_exist() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(VideoNotFoundException.class).isThrownBy(() -> videoService.findById(videoId));
    }

    @Test
    void should_expose_the_last_error_when_a_video_is_looked_up() {
        Video video = video(PipelineStatus.AI_ANALYSIS);
        video.setLastError("model unavailable");
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        assertThat(videoService.findById(video.getId()).lastError()).isEqualTo("model unavailable");
    }

    @Test
    void should_re_drive_the_pipeline_when_advancing_a_stalled_video() {
        Video video = video(PipelineStatus.AI_ANALYSIS);
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        videoService.advance(video.getId());

        verify(pipelineOrchestrator).startAsync(video.getId());
    }

    @Test
    void should_fail_when_advancing_a_video_that_is_waiting_on_review() {
        Video video = video(PipelineStatus.READY_FOR_REVIEW);
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        assertThatExceptionOfType(VideoNotResumableException.class)
                .isThrownBy(() -> videoService.advance(video.getId()));
        verifyNoInteractions(pipelineOrchestrator);
    }

    @Test
    void should_fail_when_advancing_a_published_video() {
        Video video = video(PipelineStatus.PUBLISHED);
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        assertThatExceptionOfType(VideoNotResumableException.class)
                .isThrownBy(() -> videoService.advance(video.getId()));
    }

    @Test
    void should_move_the_video_to_ready_to_publish_when_review_is_approved() {
        Video video = video(PipelineStatus.READY_FOR_REVIEW);
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        VideoResponse response = videoService.approveReview(video.getId());

        assertThat(response.status()).isEqualTo(PipelineStatus.READY_TO_PUBLISH);
        verify(videoRepository).save(video);
        verify(pipelineOrchestrator).startAsync(video.getId());
    }

    @Test
    void should_fail_when_approving_a_video_that_is_not_ready_for_review() {
        Video video = video(PipelineStatus.CLIP_RENDERING);
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));

        assertThatExceptionOfType(ReviewNotReadyException.class)
                .isThrownBy(() -> videoService.approveReview(video.getId()));
        verify(videoRepository, never()).save(any());
    }

    @Test
    void should_resolve_the_artifact_path_when_the_stage_has_produced_it() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.existsById(videoId)).thenReturn(true);
        when(storageService.exists(videoId.toString(), "transcript.json")).thenReturn(true);
        when(storageService.resolve(videoId.toString(), "transcript.json"))
                .thenReturn(Path.of("storage", videoId.toString(), "transcript.json"));

        assertThat(videoService.artifactPath(videoId, "transcript.json"))
                .isEqualTo(Path.of("storage", videoId.toString(), "transcript.json"));
    }

    @Test
    void should_fail_when_the_artifact_has_not_been_produced_yet() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.existsById(videoId)).thenReturn(true);
        when(storageService.exists(videoId.toString(), "transcript.json")).thenReturn(false);

        assertThatExceptionOfType(ArtifactNotFoundException.class)
                .isThrownBy(() -> videoService.artifactPath(videoId, "transcript.json"));
    }

    @Test
    void should_fail_when_asking_for_an_artifact_of_an_unknown_video() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.existsById(videoId)).thenReturn(false);

        assertThatExceptionOfType(VideoNotFoundException.class)
                .isThrownBy(() -> videoService.artifactPath(videoId, "transcript.json"));
        verify(storageService, never()).exists(anyString(), anyString());
    }

    @Test
    void should_name_the_clip_file_after_its_index() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.existsById(videoId)).thenReturn(true);
        when(storageService.exists(videoId.toString(), "clips/clip-3.mp4")).thenReturn(true);
        when(storageService.resolve(videoId.toString(), "clips/clip-3.mp4"))
                .thenReturn(Path.of("storage", videoId.toString(), "clips", "clip-3.mp4"));

        assertThat(videoService.clipPath(videoId, 3).getFileName()).isEqualTo(Path.of("clip-3.mp4"));
    }

    private void stubSuccessfulIngest(String youtubeId) {
        when(videoRepository.existsByYoutubeId(youtubeId)).thenReturn(false);
        when(ytDlpClient.fetchMetadata(anyString()))
                .thenReturn(new YouTubeMetadata(youtubeId, "Spring Boot 4 in anger", 840, "your_javaguy"));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Video video(PipelineStatus status) {
        return new Video(UUID.randomUUID(), "dQw4w9WgXcQ", URL, "Spring Boot 4 in anger", 840, "your_javaguy",
                status, Instant.now(), null);
    }
}
