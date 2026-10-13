package ai.dashaun.kirinuki.media;

public record MediaProfile(int width, int height, double fps, double durationSeconds, boolean hasAudio) {

    public boolean isVertical() {
        return height > width;
    }

    public double aspectRatio() {
        return width == 0 ? 0.0 : (double) width / height;
    }
}
