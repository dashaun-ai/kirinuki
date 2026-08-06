package ai.dashaun.kirinuki.review;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.dashaun.kirinuki.video.VideoResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/videos/{videoId}/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<List<ClipReviewResponse>> review(@PathVariable UUID videoId) {
        return ResponseEntity.ok(reviewService.review(videoId));
    }

    @PatchMapping("/{clipIndex}")
    public ResponseEntity<ClipReviewResponse> edit(@PathVariable UUID videoId, @PathVariable int clipIndex,
            @Valid @RequestBody EditClipContentRequest request) {
        return ResponseEntity.ok(reviewService.edit(videoId, clipIndex, request));
    }

    @PostMapping("/{clipIndex}/approve")
    public ResponseEntity<ClipReviewResponse> approveClip(@PathVariable UUID videoId, @PathVariable int clipIndex) {
        return ResponseEntity.ok(reviewService.decide(videoId, clipIndex, ReviewStatus.APPROVED));
    }

    @PostMapping("/{clipIndex}/reject")
    public ResponseEntity<ClipReviewResponse> rejectClip(@PathVariable UUID videoId, @PathVariable int clipIndex) {
        return ResponseEntity.ok(reviewService.decide(videoId, clipIndex, ReviewStatus.REJECTED));
    }

    @PostMapping("/approve")
    public ResponseEntity<VideoResponse> approve(@PathVariable UUID videoId) {
        return ResponseEntity.accepted().body(reviewService.approve(videoId));
    }
}
