package ai.dashaun.kirinuki.media;

import java.util.List;

public record FramingPlan(int width, int height, List<SpeakerSegment> segments) {

    public static FramingPlan single(int width, int height, SpeakerSegment segment) {
        return new FramingPlan(width, height, List.of(segment));
    }
}
