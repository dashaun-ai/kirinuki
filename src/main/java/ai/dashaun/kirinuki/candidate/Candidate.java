package ai.dashaun.kirinuki.candidate;

public record Candidate(
        int id,
        double start,
        double end,
        int firstWordIndex,
        int lastWordIndex,
        String text) {

    public double durationSeconds() {
        return end - start;
    }
}
