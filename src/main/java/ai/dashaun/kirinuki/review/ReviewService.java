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
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.dashaun.kirinuki.common.ClipReviewNotFoundException;
import ai.dashaun.kirinuki.common.InvalidRegenerationException;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.StaleClipReviewException;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.ContentGenerationClient;
import ai.dashaun.kirinuki.content.LinkedInVariant;
import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.content.TikTokVariant;
import ai.dashaun.kirinuki.content.XVariant;
import ai.dashaun.kirinuki.content.YouTubeVariant;
import ai.dashaun.kirinuki.edit.DesignedEdit;
import ai.dashaun.kirinuki.edit.DesignedEditRepository;
import ai.dashaun.kirinuki.edit.DesignedEditService;
import ai.dashaun.kirinuki.edit.DesignedEditStatus;
import ai.dashaun.kirinuki.edit.EditPlan;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.VideoResponse;
import ai.dashaun.kirinuki.video.VideoService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReviewService {

    private final ClipReviewRepository clipReviewRepository;
    private final VideoService videoService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ContentGenerationClient contentClient;
    private final DesignedEditRepository designedEditRepository;
    private final DesignedEditService designedEditService;

    public ReviewService(ClipReviewRepository clipReviewRepository, VideoService videoService,
            StorageService storageService, ObjectMapper objectMapper, ContentGenerationClient contentClient,
            DesignedEditRepository designedEditRepository, DesignedEditService designedEditService) {
        this.clipReviewRepository = clipReviewRepository;
        this.videoService = videoService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.contentClient = contentClient;
        this.designedEditRepository = designedEditRepository;
        this.designedEditService = designedEditService;
    }

    @Transactional(readOnly = true)
    public List<ClipReviewResponse> review(UUID videoId) {
        Map<Integer, ScoredCandidate> scored = scoredByClipIndex(videoId);
        Map<Integer, EditPlan> plans = plansByClipIndex(videoId);
        Map<Integer, DesignedEdit> designed = designedByClipIndex(videoId);
        return clipReviewRepository.findByVideoIdOrderByClipIndex(videoId).stream()
                .map(row -> toResponse(videoId, row, scored, plans, designed))
                .toList();
    }

    @Transactional
    public ClipReviewResponse edit(UUID videoId, int clipIndex, EditClipContentRequest request) {
        ClipReview row = row(videoId, clipIndex);
        if (request.version() == null || !request.version().equals(row.getVersion())) {
            throw new StaleClipReviewException(videoId, clipIndex, null);
        }
        ClipContent current = readContent(row);
        ClipContent edited = new ClipContent(
                current.clipIndex(),
                request.summary() != null ? request.summary() : current.summary(),
                request.keywords() != null ? request.keywords() : current.keywords(),
                request.tags() != null ? request.tags() : current.tags(),
                request.platforms() != null ? request.platforms() : current.platforms());
        row.setContent(writeContent(edited));
        row.setUpdatedAt(Instant.now());
        return toResponse(videoId, save(videoId, clipIndex, row), scoredByClipIndex(videoId), planFor(videoId, clipIndex),
                designedFor(videoId, clipIndex));
    }

    @Transactional
    public ClipReviewResponse decide(UUID videoId, int clipIndex, ReviewStatus status, Long version) {
        ClipReview row = row(videoId, clipIndex);
        if (version == null || !version.equals(row.getVersion())) {
            throw new StaleClipReviewException(videoId, clipIndex, null);
        }
        row.setStatus(status);
        row.setUpdatedAt(Instant.now());
        return toResponse(videoId, save(videoId, clipIndex, row), scoredByClipIndex(videoId), planFor(videoId, clipIndex),
                designedFor(videoId, clipIndex));
    }

    public VideoResponse approve(UUID videoId) {
        return videoService.approveReview(videoId);
    }

    @Transactional
    public ClipReviewResponse regenerate(UUID videoId, int clipIndex, RegenerateFieldRequest request, Long version) {
        ClipReview row = row(videoId, clipIndex);
        if (version == null || !version.equals(row.getVersion())) {
            throw new StaleClipReviewException(videoId, clipIndex, null);
        }
        Map<Integer, ScoredCandidate> scored = scoredByClipIndex(videoId);
        ScoredCandidate candidate = scored.get(clipIndex);
        String transcript = candidate == null ? "" : candidate.candidate().text();
        String videoTitle = videoService.findById(videoId).title();
        ClipContent regenerated = applyRegenerated(readContent(row), request, videoTitle, transcript);
        row.setContent(writeContent(regenerated));
        row.setUpdatedAt(Instant.now());
        return toResponse(videoId, save(videoId, clipIndex, row), scored, planFor(videoId, clipIndex),
                designedFor(videoId, clipIndex));
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
        PlatformVariant regenerated = regenerateVariant(platforms.get(index), request.field(), videoTitle, transcript);
        platforms.set(index, regenerated);
        return new ClipContent(content.clipIndex(), content.summary(), content.keywords(), content.tags(), platforms);
    }

    private PlatformVariant regenerateVariant(PlatformVariant variant, ContentField field, String videoTitle,
            String transcript) {
        String platform = variant.platform();
        String instruction = "the " + platform + " ";
        return switch (variant) {
            case XVariant x -> switch (field) {
                case TWEET -> new XVariant(platform,
                        contentClient.regenerateText(videoTitle, transcript, instruction + "tweet"),
                        x.threadSuggestion(), x.hashtags());
                case THREAD_SUGGESTION -> new XVariant(platform, x.tweet(),
                        contentClient.regenerateText(videoTitle, transcript, instruction + "thread suggestion"),
                        x.hashtags());
                case HASHTAGS -> new XVariant(platform, x.tweet(), x.threadSuggestion(),
                        contentClient.regenerateList(videoTitle, transcript,
                                instruction + "hashtags (3-6 lowercase words, without the leading '#')"));
                default -> throw invalidFieldForPlatform(field, platform);
            };
            case YouTubeVariant y -> switch (field) {
                case TITLE -> new YouTubeVariant(platform,
                        contentClient.regenerateText(videoTitle, transcript, instruction + "title"),
                        y.description(), y.seoTags());
                case DESCRIPTION -> new YouTubeVariant(platform, y.title(),
                        contentClient.regenerateText(videoTitle, transcript, instruction + "description"),
                        y.seoTags());
                case SEO_TAGS -> new YouTubeVariant(platform, y.title(), y.description(),
                        contentClient.regenerateList(videoTitle, transcript,
                                instruction + "SEO tags (3-6 lowercase words, without the leading '#')"));
                default -> throw invalidFieldForPlatform(field, platform);
            };
            case LinkedInVariant l -> switch (field) {
                case POST -> new LinkedInVariant(platform,
                        contentClient.regenerateText(videoTitle, transcript, instruction + "post"),
                        l.carouselHook(), l.hashtags(), l.callToAction());
                case CAROUSEL_HOOK -> new LinkedInVariant(platform, l.post(),
                        contentClient.regenerateText(videoTitle, transcript, instruction + "carousel hook"),
                        l.hashtags(), l.callToAction());
                case HASHTAGS -> new LinkedInVariant(platform, l.post(), l.carouselHook(),
                        contentClient.regenerateList(videoTitle, transcript,
                                instruction + "hashtags (3-6 lowercase words, without the leading '#')"),
                        l.callToAction());
                case CALL_TO_ACTION -> new LinkedInVariant(platform, l.post(), l.carouselHook(), l.hashtags(),
                        contentClient.regenerateText(videoTitle, transcript, instruction + "call to action"));
                default -> throw invalidFieldForPlatform(field, platform);
            };
            case TikTokVariant t -> switch (field) {
                case CAPTION -> new TikTokVariant(platform,
                        contentClient.regenerateText(videoTitle, transcript, instruction + "caption"),
                        t.hookText(), t.hashtags());
                case HOOK_TEXT -> new TikTokVariant(platform, t.caption(),
                        contentClient.regenerateText(videoTitle, transcript, instruction + "hook text"),
                        t.hashtags());
                case HASHTAGS -> new TikTokVariant(platform, t.caption(), t.hookText(),
                        contentClient.regenerateList(videoTitle, transcript,
                                instruction + "hashtags (3-6 lowercase words, without the leading '#')"));
                default -> throw invalidFieldForPlatform(field, platform);
            };
        };
    }

    private InvalidRegenerationException invalidFieldForPlatform(ContentField field, String platform) {
        throw new InvalidRegenerationException("Field " + field + " is not valid for platform " + platform);
    }

    private int indexOfPlatform(List<PlatformVariant> platforms, String platform) {
        for (int index = 0; index < platforms.size(); index++) {
            if (platforms.get(index).platform().equalsIgnoreCase(platform)) {
                return index;
            }
        }
        return -1;
    }

    @Transactional
    public void seedFromContent(UUID videoId, Path contentPath) {
        if (clipReviewRepository.existsByVideoId(videoId)) {
            return;
        }
        List<ClipContent> content = readList(contentPath, new TypeReference<List<ClipContent>>() {
        });
        Instant now = Instant.now();
        List<ClipReview> rows = content.stream()
                .map(clip -> new ClipReview(UUID.randomUUID(), videoId, clip.clipIndex(), ReviewStatus.PENDING,
                        writeContent(clip), now, now, null))
                .toList();
        try {
            clipReviewRepository.saveAll(rows);
        } catch (DataIntegrityViolationException exception) {
            if (!clipReviewRepository.existsByVideoId(videoId)) {
                throw exception;
            }
        }
    }

    private ClipReview save(UUID videoId, int clipIndex, ClipReview row) {
        try {
            return clipReviewRepository.saveAndFlush(row);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new StaleClipReviewException(videoId, clipIndex, exception);
        }
    }

    private ClipReview row(UUID videoId, int clipIndex) {
        return clipReviewRepository.findByVideoIdAndClipIndex(videoId, clipIndex)
                .orElseThrow(() -> new ClipReviewNotFoundException(videoId, clipIndex));
    }

    private Map<Integer, ScoredCandidate> scoredByClipIndex(UUID videoId) {
        if (!storageService.exists(videoId.toString(), Artifacts.SCORED)) {
            return Map.of();
        }
        List<ScoredCandidate> scored = readList(videoService.artifactPath(videoId, Artifacts.SCORED),
                new TypeReference<List<ScoredCandidate>>() {
                });
        Map<Integer, ScoredCandidate> byClipIndex = new HashMap<>();
        for (int index = 0; index < scored.size(); index++) {
            byClipIndex.put(index + 1, scored.get(index));
        }
        return byClipIndex;
    }

    private ClipReviewResponse toResponse(UUID videoId, ClipReview row, Map<Integer, ScoredCandidate> scored,
            Map<Integer, EditPlan> plans, Map<Integer, DesignedEdit> designed) {
        ClipContent content = readContent(row);
        ScoredCandidate candidate = scored.get(row.getClipIndex());
        String transcript = candidate == null ? null : candidate.candidate().text();
        CandidateScore score = candidate == null ? null : candidate.score();
        int overallScore = candidate == null ? 0 : candidate.overallScore();
        DesignedEdit edit = designed.get(row.getClipIndex());
        DesignedEditStatus editStatus = edit == null ? null : edit.getStatus();
        String editUrl = editStatus == DesignedEditStatus.READY
                ? "/videos/%s/clips/%d/designed".formatted(videoId, row.getClipIndex())
                : null;
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
                score,
                row.getVersion() == null ? 0 : row.getVersion(),
                plans.get(row.getClipIndex()),
                editStatus,
                editUrl);
    }

    private Map<Integer, EditPlan> plansByClipIndex(UUID videoId) {
        if (!storageService.exists(videoId.toString(), Artifacts.EDIT_PLANS)) {
            return Map.of();
        }
        List<EditPlan> plans = readList(storageService.resolve(videoId.toString(), Artifacts.EDIT_PLANS),
                new TypeReference<List<EditPlan>>() {
                });
        Map<Integer, EditPlan> byClipIndex = new HashMap<>();
        for (EditPlan plan : plans) {
            byClipIndex.put(plan.clipIndex(), plan);
        }
        return byClipIndex;
    }

    private Map<Integer, EditPlan> planFor(UUID videoId, int clipIndex) {
        EditPlan plan = plansByClipIndex(videoId).get(clipIndex);
        return plan == null ? Map.of() : Map.of(clipIndex, plan);
    }

    private Map<Integer, DesignedEdit> designedByClipIndex(UUID videoId) {
        return designedEditRepository.findByVideoIdOrderByClipIndex(videoId).stream()
                .collect(Collectors.toMap(DesignedEdit::getClipIndex, edit -> edit));
    }

    private Map<Integer, DesignedEdit> designedFor(UUID videoId, int clipIndex) {
        return designedEditRepository.findByVideoIdAndClipIndex(videoId, clipIndex)
                .map(edit -> Map.of(clipIndex, edit))
                .orElse(Map.of());
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
