package ai.dashaun.kirinuki.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.ClipReviewNotFoundException;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.video.VideoResponse;
import ai.dashaun.kirinuki.video.VideoService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReviewService {

    private final ClipReviewRepository clipReviewRepository;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    public ReviewService(ClipReviewRepository clipReviewRepository, VideoService videoService,
            ObjectMapper objectMapper) {
        this.clipReviewRepository = clipReviewRepository;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
    }

    public List<ClipReviewResponse> review(UUID videoId) {
        ensureSeeded(videoId);
        Map<Integer, ScoredCandidate> scored = scoredByClipIndex(videoId);
        return clipReviewRepository.findByVideoIdOrderByClipIndex(videoId).stream()
                .map(row -> toResponse(videoId, row, scored))
                .toList();
    }

    public ClipReviewResponse edit(UUID videoId, int clipIndex, EditClipContentRequest request) {
        ensureSeeded(videoId);
        ClipReview row = row(videoId, clipIndex);
        ClipContent current = readContent(row);
        ClipContent edited = new ClipContent(
                current.clipIndex(),
                request.summary() != null ? request.summary() : current.summary(),
                request.keywords() != null ? request.keywords() : current.keywords(),
                request.tags() != null ? request.tags() : current.tags(),
                request.platforms() != null ? request.platforms() : current.platforms());
        row.setContent(writeContent(edited));
        row.setUpdatedAt(Instant.now());
        clipReviewRepository.save(row);
        return toResponse(videoId, row, scoredByClipIndex(videoId));
    }

    public ClipReviewResponse decide(UUID videoId, int clipIndex, ReviewStatus status) {
        ensureSeeded(videoId);
        ClipReview row = row(videoId, clipIndex);
        row.setStatus(status);
        row.setUpdatedAt(Instant.now());
        clipReviewRepository.save(row);
        return toResponse(videoId, row, scoredByClipIndex(videoId));
    }

    public VideoResponse approve(UUID videoId) {
        return videoService.approveReview(videoId);
    }

    private void ensureSeeded(UUID videoId) {
        if (clipReviewRepository.existsByVideoId(videoId)) {
            return;
        }
        List<ClipContent> content = readList(videoService.artifactPath(videoId, Artifacts.CONTENT),
                new TypeReference<List<ClipContent>>() {
                });
        Instant now = Instant.now();
        List<ClipReview> rows = content.stream()
                .map(clip -> new ClipReview(UUID.randomUUID(), videoId, clip.clipIndex(), ReviewStatus.PENDING,
                        writeContent(clip), now, now))
                .toList();
        clipReviewRepository.saveAll(rows);
    }

    private ClipReview row(UUID videoId, int clipIndex) {
        return clipReviewRepository.findByVideoIdAndClipIndex(videoId, clipIndex)
                .orElseThrow(() -> new ClipReviewNotFoundException(videoId, clipIndex));
    }

    private Map<Integer, ScoredCandidate> scoredByClipIndex(UUID videoId) {
        List<ScoredCandidate> scored = readList(videoService.artifactPath(videoId, Artifacts.SCORED),
                new TypeReference<List<ScoredCandidate>>() {
                });
        Map<Integer, ScoredCandidate> byClipIndex = new HashMap<>();
        for (int index = 0; index < scored.size(); index++) {
            byClipIndex.put(index + 1, scored.get(index));
        }
        return byClipIndex;
    }

    private ClipReviewResponse toResponse(UUID videoId, ClipReview row, Map<Integer, ScoredCandidate> scored) {
        ClipContent content = readContent(row);
        ScoredCandidate candidate = scored.get(row.getClipIndex());
        String transcript = candidate == null ? null : candidate.candidate().text();
        CandidateScore score = candidate == null ? null : candidate.score();
        int overallScore = candidate == null ? 0 : candidate.overallScore();
        return new ClipReviewResponse(
                row.getClipIndex(),
                row.getStatus(),
                "/videos/%s/clips/%d".formatted(videoId, row.getClipIndex()),
                transcript,
                content.summary(),
                content.keywords(),
                content.tags(),
                content.platforms(),
                overallScore,
                score);
    }

    private ClipContent readContent(ClipReview row) {
        return objectMapper.readValue(row.getContent(), ClipContent.class);
    }

    private String writeContent(ClipContent content) {
        return objectMapper.writeValueAsString(content);
    }

    private <T> T readList(Path path, TypeReference<T> type) {
        try {
            return objectMapper.readValue(Files.readString(path), type);
        } catch (IOException exception) {
            throw new KirinukiException("Could not read " + path, exception);
        }
    }
}
