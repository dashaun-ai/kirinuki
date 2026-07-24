package ai.dashaun.kirinuki.pipeline;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PipelineOrchestrator(VideoRepository videoRepository, StorageService storageService,
            List<PipelineStage> stages) {
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.stages = stages;
    }

    public void startAsync(UUID videoId) {
        if (!inFlight.add(videoId)) {
            log.info("Pipeline already running for video {}", videoId);
            return;
        }
        Thread.ofVirtual().name("pipeline-" + videoId).start(() -> {
            try {
                advance(videoId);
            } finally {
                inFlight.remove(videoId);
            }
        });
    }

    public boolean isRunning(UUID videoId) {
        return inFlight.contains(videoId);
    }

    public boolean hasPendingWork(UUID videoId) {
        return stages.stream().anyMatch(stage -> !storageService.exists(videoId.toString(), stage.artifact()));
    }

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
        String videoId = video.getId().toString();
        moveTo(video, stage.status());
        if (!storageService.exists(videoId, stage.artifact())) {
            try {
                stage.run(video);
            } catch (RuntimeException exception) {
                storageService.discardTemporary(videoId, stage.artifact());
                throw exception;
            }
            storageService.commit(videoId, stage.artifact());
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
