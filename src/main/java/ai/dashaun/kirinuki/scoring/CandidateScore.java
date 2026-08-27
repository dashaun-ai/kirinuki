package ai.dashaun.kirinuki.scoring;

import java.util.List;

public record CandidateScore(
        int hook,
        int educationalValue,
        int emotion,
        int virality,
        List<String> suggestedPlatforms,
        String reason) {

    public CandidateScore clamped() {
        return new CandidateScore(clamp(hook), clamp(educationalValue), clamp(emotion), clamp(virality),
                suggestedPlatforms, reason);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(10, value));
    }
}
