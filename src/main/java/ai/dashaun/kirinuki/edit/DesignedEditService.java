package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import ai.dashaun.kirinuki.video.VideoRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class DesignedEditService {

    private static final Logger log = LoggerFactory.getLogger(DesignedEditService.class);
    private static final int MAX_CONCURRENT_RENDERS = 1;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_RENDERS);
    private final DesignedEditRepository repository;
    private final DesignedEditRenderer renderer;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final KirinukiPipelineProperties properties;
    private final VideoRepository videoRepository;

    public DesignedEditService(DesignedEditRepository repository, DesignedEditRenderer renderer,
            StorageService storageService, ObjectMapper objectMapper, KirinukiPipelineProperties properties,
            VideoRepository videoRepository) {
        this.repository = repository;
        this.renderer = renderer;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.videoRepository = videoRepository;
    }

    public void queueAutomaticRenders(UUID videoId) {
        KirinukiPipelineProperties.Edit edit = properties.edit();
        if (!edit.enabled() || !edit.autoRender()) {
            return;
        }
        List<ScoredCandidate> scored = readScored(videoId);
        Map<Integer, EditPlan> plansByIndex = readPlans(videoId);
        record IndexedScored(int clipIndex, ScoredCandidate scored) {
        }
        List<IndexedScored> top = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            top.add(new IndexedScored(index + 1, scored.get(index)));
        }
        top.sort(Comparator.comparingInt((IndexedScored item) -> item.scored().overallScore()).reversed());
        if (top.size() > edit.topClips()) {
            top = top.subList(0, edit.topClips());
        }

        for (IndexedScored item : top) {
            int clipIndex = item.clipIndex();
            EditPlan plan = plansByIndex.get(clipIndex);
            if (plan == null) {
                continue;
            }
            if (repository.existsByVideoIdAndClipIndex(videoId, clipIndex)) {
                continue;
            }
            createAndStart(videoId, clipIndex, plan, item.scored());
        }
    }

    public void render(UUID videoId, int clipIndex) {
        DesignedEdit row = repository.findByVideoIdAndClipIndex(videoId, clipIndex)
                .orElseThrow(() -> new DesignedEditNotFoundException(videoId, clipIndex));
        if (row.getStatus() == DesignedEditStatus.RENDERING || row.getStatus() == DesignedEditStatus.READY) {
            return;
        }
        Map<Integer, EditPlan> plans = readPlans(videoId);
        EditPlan plan = plans.get(clipIndex);
        if (plan == null) {
            throw new DesignedEditNotFoundException(videoId, clipIndex);
        }
        List<ScoredCandidate> scored = readScored(videoId);
        if (clipIndex < 1 || clipIndex > scored.size()) {
            throw new DesignedEditNotFoundException(videoId, clipIndex);
        }
        startRender(row, plan, scored.get(clipIndex - 1));
    }

    private void createAndStart(UUID videoId, int clipIndex, EditPlan plan, ScoredCandidate scored) {
        Instant now = Instant.now();
        DesignedEdit row = new DesignedEdit(UUID.randomUUID(), videoId, clipIndex, DesignedEditStatus.QUEUED, null,
                null, now, now);
        try {
            repository.save(row);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Designed edit for video {} clip {} already exists", videoId, clipIndex);
            return;
        }
        startRender(row, plan, scored);
    }

    private void startRender(DesignedEdit row, EditPlan plan, ScoredCandidate scored) {
        Thread.ofVirtual().name("designed-edit-" + row.getVideoId() + "-" + row.getClipIndex()).start(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                update(row, DesignedEditStatus.FAILED, "Render was interrupted");
                return;
            }
            try {
                if (!update(row, DesignedEditStatus.RENDERING, null)) {
                    return;
                }
                Path source = storageService.resolve(row.getVideoId().toString(), Artifacts.SOURCE);
                Path transcript = storageService.resolve(row.getVideoId().toString(), Artifacts.TRANSCRIPT);
                Path output = resolveOutputPath(row.getVideoId(), row.getClipIndex());
                try {
                    Files.createDirectories(output.getParent());
                } catch (IOException exception) {
                    throw new KirinukiException("Could not create designed output directory " + output.getParent(),
                            exception);
                }
                String title = videoRepository.findById(row.getVideoId())
                        .map(Video::getTitle)
                        .orElse(null);
                renderer.render(source, transcript, scored, plan, title, output);
                update(row, DesignedEditStatus.READY, null);
            } catch (RuntimeException exception) {
                log.error("Designed edit failed for video {} clip {}", row.getVideoId(), row.getClipIndex(),
                        exception);
                update(row, DesignedEditStatus.FAILED,
                        exception.getMessage() == null ? exception.toString() : exception.getMessage());
            } finally {
                semaphore.release();
            }
        });
    }

    private boolean update(DesignedEdit row, DesignedEditStatus status, String lastError) {
        try {
            row.setStatus(status);
            row.setLastError(lastError);
            row.setUpdatedAt(Instant.now());
            DesignedEdit saved = repository.save(row);
            row.setVersion(saved.getVersion());
            return true;
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.warn("Designed edit {} was updated concurrently", row.getId(), exception);
            return false;
        }
    }

    public Path outputPath(UUID videoId, int clipIndex) {
        return resolveOutputPath(videoId, clipIndex);
    }

    private Path resolveOutputPath(UUID videoId, int clipIndex) {
        return storageService.videoDirectory(videoId.toString())
                .resolve(Artifacts.DESIGNED_DIRECTORY)
                .resolve("clip-" + clipIndex)
                .resolve("designed.mp4");
    }

    public boolean isReady(UUID videoId, int clipIndex) {
        return repository.findByVideoIdAndClipIndex(videoId, clipIndex)
                .map(row -> row.getStatus() == DesignedEditStatus.READY)
                .orElse(false);
    }

    public List<DesignedEdit> findByVideoId(UUID videoId) {
        return repository.findByVideoIdOrderByClipIndex(videoId);
    }

    private List<ScoredCandidate> readScored(UUID videoId) {
        Path path = storageService.resolve(videoId.toString(), Artifacts.SCORED);
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scored candidates for designed edits " + path, exception);
        }
    }

    private Map<Integer, EditPlan> readPlans(UUID videoId) {
        Path path = storageService.resolve(videoId.toString(), Artifacts.EDIT_PLANS);
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            List<EditPlan> plans = objectMapper.readValue(Files.readString(path), new TypeReference<List<EditPlan>>() {
            });
            return plans.stream().collect(Collectors.toMap(EditPlan::clipIndex, plan -> plan));
        } catch (IOException exception) {
            throw new KirinukiException("Could not read edit plans for designed edits " + path, exception);
        }
    }
}
