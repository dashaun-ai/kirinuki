package ai.dashaun.kirinuki.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoRepository;

class PipelineOrchestratorTest {

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final StorageService storageService = mock(StorageService.class);

    private Video video;

    @BeforeEach
    void setUp() {
        video = new Video(UUID.randomUUID(), "dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", "A talk", 840,
                "your_javaguy", PipelineStatus.DOWNLOADING, Instant.now(), "previous failure");
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));
    }

    @Test
    void should_run_the_stage_when_its_artifact_is_missing() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        when(storageService.exists(video.getId().toString(), "source.mp4")).thenReturn(false);

        orchestrator(download).advance(video.getId());

        verify(download).run(video);
    }

    @Test
    void should_commit_the_artifact_when_the_stage_succeeds() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        when(storageService.exists(video.getId().toString(), "source.mp4")).thenReturn(false);

        orchestrator(download).advance(video.getId());

        verify(storageService).commit(video.getId().toString(), "source.mp4");
    }

    @Test
    void should_advance_the_status_when_the_stage_completes() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");

        orchestrator(download).advance(video.getId());

        assertThat(video.getStatus()).isEqualTo(PipelineStatus.MEDIA_PREPARATION);
        verify(videoRepository).save(video);
    }

    @Test
    void should_clear_a_previous_error_when_the_pipeline_is_re_driven() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");

        orchestrator(download).advance(video.getId());

        assertThat(video.getLastError()).isNull();
    }

    @Test
    void should_skip_the_stage_when_its_artifact_already_exists() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        when(storageService.exists(video.getId().toString(), "source.mp4")).thenReturn(true);

        orchestrator(download).advance(video.getId());

        verify(download, never()).run(any());
        verify(storageService, never()).commit(anyString(), anyString());
    }

    @Test
    void should_still_advance_the_status_when_the_stage_is_skipped() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        when(storageService.exists(video.getId().toString(), "source.mp4")).thenReturn(true);

        orchestrator(download).advance(video.getId());

        assertThat(video.getStatus()).isEqualTo(PipelineStatus.MEDIA_PREPARATION);
    }

    @Test
    void should_run_consecutive_stages_until_a_status_has_none() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        PipelineStage media = stage(PipelineStatus.MEDIA_PREPARATION, "audio.wav");

        orchestrator(download, media).advance(video.getId());

        verify(download).run(video);
        verify(media).run(video);
        assertThat(video.getStatus()).isEqualTo(PipelineStatus.FEATURE_EXTRACTION);
    }

    @Test
    void should_run_every_sibling_stage_when_a_status_fans_out() {
        video.setStatus(PipelineStatus.FEATURE_EXTRACTION);
        PipelineStage scenes = stage(PipelineStatus.FEATURE_EXTRACTION, "scenes.json");
        PipelineStage audio = stage(PipelineStatus.FEATURE_EXTRACTION, "audio-features.json");

        orchestrator(scenes, audio).advance(video.getId());

        verify(scenes).run(video);
        verify(audio).run(video);
        assertThat(video.getStatus()).isEqualTo(PipelineStatus.CANDIDATE_GENERATION);
    }

    @Test
    void should_discard_the_partial_artifact_when_a_stage_fails() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        doThrow(new KirinukiException("yt-dlp exited with 1")).when(download).run(video);

        orchestrator(download).advance(video.getId());

        verify(storageService).discardTemporary(video.getId().toString(), "source.mp4");
        verify(storageService, never()).commit(anyString(), anyString());
    }

    @Test
    void should_leave_the_status_untouched_when_a_stage_fails() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        doThrow(new KirinukiException("yt-dlp exited with 1")).when(download).run(video);

        orchestrator(download).advance(video.getId());

        assertThat(video.getStatus()).isEqualTo(PipelineStatus.DOWNLOADING);
    }

    @Test
    void should_record_the_reason_on_the_video_when_a_stage_fails() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        doThrow(new KirinukiException("yt-dlp exited with 1")).when(download).run(video);

        orchestrator(download).advance(video.getId());

        assertThat(video.getLastError()).isEqualTo("yt-dlp exited with 1");
        verify(videoRepository).save(video);
    }

    @Test
    void should_truncate_the_recorded_error_when_the_tool_output_is_huge() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        doThrow(new KirinukiException("x".repeat(4096))).when(download).run(video);

        orchestrator(download).advance(video.getId());

        assertThat(video.getLastError()).hasSize(1024);
    }

    @Test
    void should_record_the_exception_type_when_the_failure_carries_no_message() {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        doThrow(new IllegalStateException()).when(download).run(video);

        orchestrator(download).advance(video.getId());

        assertThat(video.getLastError()).contains("IllegalStateException");
    }

    @Test
    void should_report_the_failure_when_one_of_the_fanned_out_stages_fails() {
        video.setStatus(PipelineStatus.FEATURE_EXTRACTION);
        PipelineStage scenes = stage(PipelineStatus.FEATURE_EXTRACTION, "scenes.json");
        PipelineStage audio = stage(PipelineStatus.FEATURE_EXTRACTION, "audio-features.json");
        doThrow(new KirinukiException("ffmpeg exited with 1")).when(audio).run(video);

        orchestrator(scenes, audio).advance(video.getId());

        assertThat(video.getLastError()).isEqualTo("ffmpeg exited with 1");
        assertThat(video.getStatus()).isEqualTo(PipelineStatus.FEATURE_EXTRACTION);
        verify(scenes).run(video);
    }

    @Test
    void should_stay_quiet_when_the_video_no_longer_exists() {
        UUID unknownId = UUID.randomUUID();
        when(videoRepository.findById(unknownId)).thenReturn(Optional.empty());

        orchestrator(stage(PipelineStatus.DOWNLOADING, "source.mp4")).advance(unknownId);

        verify(videoRepository, never()).save(any());
    }

    @Test
    void should_run_the_pipeline_once_when_the_same_video_is_started_twice() throws InterruptedException {
        PipelineStage download = stage(PipelineStatus.DOWNLOADING, "source.mp4");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(download).run(video);
        PipelineOrchestrator orchestrator = orchestrator(download);

        orchestrator.startAsync(video.getId());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.startAsync(video.getId());
        release.countDown();

        verify(download, timeout(5000).times(1)).run(video);
    }

    private PipelineOrchestrator orchestrator(PipelineStage... stages) {
        return new PipelineOrchestrator(videoRepository, storageService, List.of(stages));
    }

    private PipelineStage stage(PipelineStatus status, String artifact) {
        PipelineStage stage = mock(PipelineStage.class);
        when(stage.status()).thenReturn(status);
        when(stage.artifact()).thenReturn(artifact);
        when(storageService.exists(eq(video.getId().toString()), eq(artifact))).thenReturn(false);
        return stage;
    }
}
