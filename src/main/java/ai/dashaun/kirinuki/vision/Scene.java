package ai.dashaun.kirinuki.vision;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record Scene(
        List<SceneSpeaker> speakers,
        List<SpeakerActivity> activeSpeaker,
        ScreenRegion screenRegion,
        List<Double> screenChanges,
        boolean hasCode,
        FaceTracks faceTracks) {

    public Scene {
        if (speakers == null) {
            speakers = List.of();
        }
        if (activeSpeaker == null) {
            activeSpeaker = List.of();
        }
        if (screenChanges == null) {
            screenChanges = List.of();
        }
        if (faceTracks == null) {
            faceTracks = FaceTracks.empty();
        }
    }

    public Optional<String> activeSpeaker(double at) {
        return activeSpeaker.stream()
                .filter(activity -> activity.from() <= at && activity.to() >= at)
                .max(Comparator.comparingDouble(SpeakerActivity::confidence))
                .map(SpeakerActivity::speakerId);
    }

    public List<String> activeSpeakers(double from, double to) {
        return activeSpeaker.stream()
                .filter(activity -> activity.to() > from && activity.from() < to)
                .map(SpeakerActivity::speakerId)
                .distinct()
                .toList();
    }

    public Optional<FaceTrack> faceTrack(String speakerId) {
        return faceTracks.tracks().stream()
                .filter(track -> String.valueOf(track.id()).equals(speakerId))
                .findFirst();
    }
}
