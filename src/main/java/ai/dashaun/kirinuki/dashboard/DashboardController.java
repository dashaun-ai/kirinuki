package ai.dashaun.kirinuki.dashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.review.ContentField;
import ai.dashaun.kirinuki.review.EditClipContentRequest;
import ai.dashaun.kirinuki.review.RegenerateFieldRequest;
import ai.dashaun.kirinuki.review.ReviewService;
import ai.dashaun.kirinuki.review.ReviewStatus;
import ai.dashaun.kirinuki.video.VideoResponse;
import ai.dashaun.kirinuki.video.VideoService;

@Controller
public class DashboardController {

    private final VideoService videoService;
    private final ReviewService reviewService;

    public DashboardController(VideoService videoService, ReviewService reviewService) {
        this.videoService = videoService;
        this.reviewService = reviewService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("videos", videoService.findAll());
        return "videos";
    }

    @PostMapping("/ingest")
    public String ingest(@RequestParam String url) {
        videoService.ingest(url);
        return "redirect:/";
    }

    @GetMapping("/videos/{videoId}/dashboard")
    public String dashboard(@PathVariable UUID videoId, Model model) {
        VideoResponse video = videoService.findById(videoId);
        model.addAttribute("video", video);
        if (video.status().ordinal() < PipelineStatus.READY_FOR_REVIEW.ordinal()) {
            model.addAttribute("steps", pipelineSteps(video.status()));
            return "processing";
        }
        model.addAttribute("clips", reviewService.review(videoId));
        model.addAttribute("fields", ContentField.values());
        return "dashboard";
    }

    @PostMapping("/videos/{videoId}/dashboard/retry")
    public String retry(@PathVariable UUID videoId) {
        videoService.advance(videoId);
        return "redirect:/videos/" + videoId + "/dashboard";
    }

    @PostMapping("/videos/{videoId}/dashboard/clips/{clipIndex}/edit")
    public String edit(@PathVariable UUID videoId, @PathVariable int clipIndex, ClipEditForm form) {
        reviewService.edit(videoId, clipIndex, toRequest(form));
        return redirect(videoId);
    }

    @PostMapping("/videos/{videoId}/dashboard/clips/{clipIndex}/approve")
    public String approveClip(@PathVariable UUID videoId, @PathVariable int clipIndex) {
        reviewService.decide(videoId, clipIndex, ReviewStatus.APPROVED);
        return redirect(videoId);
    }

    @PostMapping("/videos/{videoId}/dashboard/clips/{clipIndex}/reject")
    public String rejectClip(@PathVariable UUID videoId, @PathVariable int clipIndex) {
        reviewService.decide(videoId, clipIndex, ReviewStatus.REJECTED);
        return redirect(videoId);
    }

    @PostMapping("/videos/{videoId}/dashboard/clips/{clipIndex}/regenerate")
    public String regenerate(@PathVariable UUID videoId, @PathVariable int clipIndex,
            @RequestParam ContentField field, @RequestParam(required = false) String platform) {
        reviewService.regenerate(videoId, clipIndex, new RegenerateFieldRequest(field, platform));
        return redirect(videoId);
    }

    @PostMapping("/videos/{videoId}/dashboard/approve")
    public String approveVideo(@PathVariable UUID videoId) {
        reviewService.approve(videoId);
        return redirect(videoId);
    }

    private String redirect(UUID videoId) {
        return "redirect:/videos/" + videoId + "/dashboard";
    }

    private static final List<PipelineStatus> PIPELINE = List.of(
            PipelineStatus.DOWNLOADING, PipelineStatus.MEDIA_PREPARATION, PipelineStatus.FEATURE_EXTRACTION,
            PipelineStatus.CANDIDATE_GENERATION, PipelineStatus.AI_ANALYSIS, PipelineStatus.CLIP_RENDERING,
            PipelineStatus.CONTENT_GENERATION, PipelineStatus.READY_FOR_REVIEW);

    private List<PipelineStep> pipelineSteps(PipelineStatus current) {
        List<PipelineStep> steps = new ArrayList<>();
        for (PipelineStatus status : PIPELINE) {
            String state = status.ordinal() < current.ordinal() ? "done"
                    : status.ordinal() == current.ordinal() ? "active" : "todo";
            steps.add(new PipelineStep(label(status), state));
        }
        return steps;
    }

    private String label(PipelineStatus status) {
        return switch (status) {
            case DOWNLOADING -> "Download";
            case MEDIA_PREPARATION -> "Media preparation";
            case FEATURE_EXTRACTION -> "Feature extraction";
            case CANDIDATE_GENERATION -> "Candidate generation";
            case AI_ANALYSIS -> "Scoring candidates";
            case CLIP_RENDERING -> "Rendering clips";
            case CONTENT_GENERATION -> "Content generation";
            case READY_FOR_REVIEW -> "Ready for review";
            default -> status.name();
        };
    }

    private EditClipContentRequest toRequest(ClipEditForm form) {
        List<PlatformVariant> platforms = form.getPlatforms().stream()
                .map(platform -> new PlatformVariant(platform.getPlatform(), platform.getTitle(),
                        platform.getCaption(), splitCsv(platform.getHashtags()), platform.getCallToAction()))
                .toList();
        return new EditClipContentRequest(form.getVersion(), form.getSummary(), splitCsv(form.getKeywords()),
                splitCsv(form.getTags()), platforms);
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }
}
