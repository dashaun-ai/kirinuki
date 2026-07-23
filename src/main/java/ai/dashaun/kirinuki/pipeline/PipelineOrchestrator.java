package ai.dashaun.kirinuki.pipeline;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.VideoNotFoundException;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoRepository;

@Service
public class PipelineOrchestrator {

    private static final int MAX_ERROR_LENGTH = 1024;
    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final VideoRepository videoRepository;
    private final StorageService storageService;
    private final List<PipelineStage> stages;

    public PipelineOrchestrator(VideoRepository videoRepository, StorageService storageService,
            List<PipelineStage> stages) {
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.stages = stages;
    }

    public void startAsync(UUID videoId) {
        Thread.ofVirtual().name("pipeline-" + videoId).start(() -> advance(videoId));
    }

    // A failed stage leaves the video parked at its current status; re-driving skips whatever already
    // produced an artifact, so only the interrupted stage repeats.
    public void advance(UUID videoId) {
        try {
            Video video = videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId));
            video.setLastError(null);
            for (PipelineStage stage : stages) {
                runStage(stage, video);
            }
            log.info("Pipeline reached {} for video {}", video.getStatus(), videoId);
        } catch (RuntimeException exception) {
            log.error("Pipeline stalled for video {}", videoId, exception);
            recordFailure(videoId, exception);
        }
    }

    private void runStage(PipelineStage stage, Video video) {
        moveTo(video, stage.status());
        if (!storageService.exists(video.getId().toString(), stage.artifact())) {
            stage.run(video);
        }
        moveTo(video, stage.completedStatus());
    }

    private void recordFailure(UUID videoId, RuntimeException exception) {
        videoRepository.findById(videoId).ifPresent(video -> {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            video.setLastError(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
            videoRepository.save(video);
        });
    }

    private void moveTo(Video video, PipelineStatus status) {
        if (video.getStatus() == status) {
            return;
        }
        video.setStatus(status);
        videoRepository.save(video);
    }
}
