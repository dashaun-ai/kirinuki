package ai.dashaun.kirinuki.review;

import java.util.List;

import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.scoring.CandidateScore;

public record ClipReviewResponse(
        int clipIndex,
        ReviewStatus status,
        String downloadUrl,
        String transcript,
        String summary,
        List<String> keywords,
        List<String> tags,
        List<PlatformVariant> platforms,
        int overallScore,
        CandidateScore score,
        long version) {
}
