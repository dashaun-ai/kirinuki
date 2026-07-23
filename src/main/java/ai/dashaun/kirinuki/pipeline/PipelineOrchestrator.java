package ai.dashaun.kirinuki.pipeline;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.VideoNotFoundException;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.media.WhisperClient;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoRepository;

@Service
public class PipelineOrchestrator {

    public static final String SOURCE = "source.mp4";
    public static final String AUDIO = "audio.wav";
    public static final String TRANSCRIPT = "transcript.json";

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final VideoRepository videoRepository;
    private final StorageService storageService;
    private final FfmpegClient ffmpegClient;
    private final WhisperClient whisperClient;

    public PipelineOrchestrator(VideoRepository videoRepository, StorageService storageService,
            FfmpegClient ffmpegClient, WhisperClient whisperClient) {
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.ffmpegClient = ffmpegClient;
        this.whisperClient = whisperClient;
    }

    public void startAsync(UUID videoId) {
        Thread.ofVirtual().name("pipeline-" + videoId).start(() -> advance(videoId));
    }

    // A failed stage leaves the video parked at its current status; re-driving skips whatever already
    // produced an artifact, so only the interrupted stage repeats.
    public void advance(UUID videoId) {
        try {
            Video video = videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId));
            prepareMedia(video);
            transcribe(video);
            log.info("Pipeline reached {} for video {}", video.getStatus(), videoId);
        } catch (RuntimeException exception) {
            log.error("Pipeline stalled for video {}", videoId, exception);
        }
    }

    private void prepareMedia(Video video) {
        String videoId = video.getId().toString();
        moveTo(video, PipelineStatus.MEDIA_PREPARATION);
        if (storageService.exists(videoId, AUDIO)) {
            return;
        }
        ffmpegClient.extractAudio(storageService.resolve(videoId, SOURCE), storageService.prepareFor(videoId, AUDIO));
    }

    private void transcribe(Video video) {
        String videoId = video.getId().toString();
        moveTo(video, PipelineStatus.FEATURE_EXTRACTION);
        if (!storageService.exists(videoId, TRANSCRIPT)) {
            whisperClient.transcribe(storageService.resolve(videoId, AUDIO),
                    storageService.prepareFor(videoId, TRANSCRIPT));
        }
        moveTo(video, PipelineStatus.WAITING_FOR_FEATURES);
    }

    private void moveTo(Video video, PipelineStatus status) {
        video.setStatus(status);
        videoRepository.save(video);
    }
}
