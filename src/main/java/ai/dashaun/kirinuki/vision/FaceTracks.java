package ai.dashaun.kirinuki.vision;

import java.util.List;

public record FaceTracks(int frameWidth, int frameHeight, List<FaceTrack> tracks) {

    public static FaceTracks empty() {
        return new FaceTracks(0, 0, List.of());
    }
}
