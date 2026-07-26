package ai.dashaun.kirinuki.pipeline;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private final Map<PipelineStatus, PipelineStage> stagesByStatus;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PipelineOrchestrator(VideoRepository videoRepository, StorageService storageService,
            List<PipelineStage> stages) {
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.stagesByStatus = new EnumMap<>(PipelineStatus.class);
        stages.forEach(stage -> stagesByStatus.put(stage.status(), stage));
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

    public void advance(UUID videoId) {
        try {
            Video video = videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId));
            video.setLastError(null);
            for (PipelineStage stage = stagesByStatus.get(video.getStatus()); stage != null;
                    stage = stagesByStatus.get(video.getStatus())) {
                runStage(stage, video);
            }
            log.info("Pipeline paused at {} for video {}", video.getStatus(), videoId);
        } catch (RuntimeException exception) {
            log.error("Pipeline stalled for video {}", videoId, exception);
            recordFailure(videoId, exception);
        }
    }

    private void runStage(PipelineStage stage, Video video) {
        String videoId = video.getId().toString();
        if (!storageService.exists(videoId, stage.artifact())) {
            try {
                stage.run(video);
            } catch (RuntimeException exception) {
                storageService.discardTemporary(videoId, stage.artifact());
                throw exception;
            }
            storageService.commit(videoId, stage.artifact());
        }
        video.setStatus(video.getStatus().next());
        videoRepository.save(video);
    }

    private void recordFailure(UUID videoId, RuntimeException exception) {
        videoRepository.findById(videoId).ifPresent(video -> {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            video.setLastError(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
            videoRepository.save(video);
        });
    }
}
