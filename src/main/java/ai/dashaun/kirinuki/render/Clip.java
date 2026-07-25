package ai.dashaun.kirinuki.render;

public record Clip(
        int index,
        double start,
        double end,
        int overallScore,
        String reason,
        String video) {
}
