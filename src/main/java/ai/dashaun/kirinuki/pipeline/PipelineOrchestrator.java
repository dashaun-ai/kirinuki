package ai.dashaun.kirinuki.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.KirinukiException;
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
    private final Map<PipelineStatus, List<PipelineStage>> stagesByStatus;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PipelineOrchestrator(VideoRepository videoRepository, StorageService storageService,
            List<PipelineStage> stages) {
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.stagesByStatus = stages.stream().collect(Collectors.groupingBy(PipelineStage::status));
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
            boolean ranAnyStage = false;
            for (List<PipelineStage> stages = stagesByStatus.get(video.getStatus()); stages != null;
                    stages = stagesByStatus.get(video.getStatus())) {
                runStages(stages, video);
                ranAnyStage = true;
            }
            if (!ranAnyStage) {
                videoRepository.save(video);
            }
            log.info("Pipeline paused at {} for video {}", video.getStatus(), videoId);
        } catch (RuntimeException exception) {
            log.error("Pipeline stalled for video {}", videoId, exception);
            recordFailure(videoId, exception);
        }
    }

    private void runStages(List<PipelineStage> stages, Video video) {
        if (stages.size() == 1) {
            runArtifact(stages.get(0), video);
        } else {
            fanOut(stages, video);
        }
        video.setStatus(video.getStatus().next());
        videoRepository.save(video);
    }

    private void fanOut(List<PipelineStage> stages, Video video) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = stages.stream()
                    .map(stage -> executor.submit(() -> runArtifact(stage, video)))
                    .toList();
            List<RuntimeException> failures = new ArrayList<>();
            for (var future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause() instanceof RuntimeException runtime ? runtime
                            : new KirinukiException("Feature extraction failed", exception.getCause()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failures.add(new KirinukiException("Feature extraction interrupted", exception));
                }
            }
            if (!failures.isEmpty()) {
                RuntimeException failure = failures.getFirst();
                failures.stream().skip(1).forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    private void runArtifact(PipelineStage stage, Video video) {
        String videoId = video.getId().toString();
        if (storageService.exists(videoId, stage.artifact())) {
            return;
        }
        log.info("Video {} → running stage {}", videoId, stage.status());
        try {
            stage.run(video);
        } catch (RuntimeException exception) {
            storageService.discardTemporary(videoId, stage.artifact());
            throw exception;
        }
        storageService.commit(videoId, stage.artifact());
    }

    private void recordFailure(UUID videoId, RuntimeException exception) {
        videoRepository.findById(videoId).ifPresent(video -> {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            video.setLastError(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
            videoRepository.save(video);
        });
    }
}
