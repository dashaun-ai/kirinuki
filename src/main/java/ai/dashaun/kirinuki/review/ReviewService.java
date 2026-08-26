package ai.dashaun.kirinuki.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.ClipReviewNotFoundException;
import ai.dashaun.kirinuki.common.InvalidRegenerationException;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.ContentGenerationClient;
import ai.dashaun.kirinuki.content.PlatformVariant;
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
    private final ContentGenerationClient contentClient;

    public ReviewService(ClipReviewRepository clipReviewRepository, VideoService videoService,
            ObjectMapper objectMapper, ContentGenerationClient contentClient) {
        this.clipReviewRepository = clipReviewRepository;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
        this.contentClient = contentClient;
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

    public ClipReviewResponse regenerate(UUID videoId, int clipIndex, RegenerateFieldRequest request) {
        ensureSeeded(videoId);
        ClipReview row = row(videoId, clipIndex);
        Map<Integer, ScoredCandidate> scored = scoredByClipIndex(videoId);
        ScoredCandidate candidate = scored.get(clipIndex);
        String transcript = candidate == null ? "" : candidate.candidate().text();
        String videoTitle = videoService.findById(videoId).title();
        ClipContent regenerated = applyRegenerated(readContent(row), request, videoTitle, transcript);
        row.setContent(writeContent(regenerated));
        row.setUpdatedAt(Instant.now());
        clipReviewRepository.save(row);
        return toResponse(videoId, row, scored);
    }

    private ClipContent applyRegenerated(ClipContent content, RegenerateFieldRequest request, String videoTitle,
            String transcript) {
        if (request.field().isPlatformScoped()) {
            return applyPlatformField(content, request, videoTitle, transcript);
        }
        return applyTopLevelField(content, request.field(), videoTitle, transcript);
    }

    private ClipContent applyTopLevelField(ClipContent content, ContentField field, String videoTitle,
            String transcript) {
        return switch (field) {
            case SUMMARY -> new ClipContent(content.clipIndex(),
                    contentClient.regenerateText(videoTitle, transcript, "the summary"),
                    content.keywords(), content.tags(), content.platforms());
            case KEYWORDS -> new ClipContent(content.clipIndex(), content.summary(),
                    contentClient.regenerateList(videoTitle, transcript,
                            "the keywords (4-8 lowercase search terms)"),
                    content.tags(), content.platforms());
            case TAGS -> new ClipContent(content.clipIndex(), content.summary(), content.keywords(),
                    contentClient.regenerateList(videoTitle, transcript,
                            "the tags (3-6 short lowercase hashtag words, without the leading '#')"),
                    content.platforms());
            default -> throw new InvalidRegenerationException("Field " + field + " is not a top-level field");
        };
    }

    private ClipContent applyPlatformField(ClipContent content, RegenerateFieldRequest request, String videoTitle,
            String transcript) {
        String platform = request.platform();
        if (platform == null || platform.isBlank()) {
            throw new InvalidRegenerationException("Field " + request.field() + " requires a platform");
        }
        List<PlatformVariant> platforms = new ArrayList<>(content.platforms());
        int index = indexOfPlatform(platforms, platform);
        if (index < 0) {
            throw new InvalidRegenerationException("Platform " + platform + " not found for this clip");
        }
        PlatformVariant variant = platforms.get(index);
        PlatformVariant regenerated = switch (request.field()) {
            case TITLE -> new PlatformVariant(variant.platform(),
                    contentClient.regenerateText(videoTitle, transcript, "the " + platform + " title"),
                    variant.caption(), variant.hashtags(), variant.callToAction());
            case CAPTION -> new PlatformVariant(variant.platform(), variant.title(),
                    contentClient.regenerateText(videoTitle, transcript,
                            "the " + platform + " caption (the post body tuned to " + platform + ")"),
                    variant.hashtags(), variant.callToAction());
            case HASHTAGS -> new PlatformVariant(variant.platform(), variant.title(), variant.caption(),
                    contentClient.regenerateList(videoTitle, transcript,
                            "the " + platform + " hashtags (3-6 lowercase words, without the leading '#')"),
                    variant.callToAction());
            case CALL_TO_ACTION -> new PlatformVariant(variant.platform(), variant.title(), variant.caption(),
                    variant.hashtags(),
                    contentClient.regenerateText(videoTitle, transcript, "the " + platform + " call to action"));
            default -> throw new InvalidRegenerationException("Field " + request.field() + " is not a platform field");
        };
        platforms.set(index, regenerated);
        return new ClipContent(content.clipIndex(), content.summary(), content.keywords(), content.tags(), platforms);
    }

    private int indexOfPlatform(List<PlatformVariant> platforms, String platform) {
        for (int index = 0; index < platforms.size(); index++) {
            if (platforms.get(index).platform().equalsIgnoreCase(platform)) {
                return index;
            }
        }
        return -1;
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
        try {
            clipReviewRepository.saveAll(rows);
        } catch (DataIntegrityViolationException exception) {
            if (!clipReviewRepository.existsByVideoId(videoId)) {
                throw exception;
            }
        }
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
