package ai.dashaun.kirinuki.vision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class SpeakerTrackerTest {

    private static final KirinukiPipelineProperties PROPERTIES = new Binder(
            new MapConfigurationPropertySource(java.util.Map.of()))
                    .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);

    private final SpeakerTracker tracker = new SpeakerTracker(PROPERTIES);

    @Test
    void should_return_empty_when_no_face_tracks() {
        List<SpeakerActivity> activities = tracker.track(FaceTracks.empty(), words(), 0.0, 10.0);

        assertThat(activities).isEmpty();
    }

    @Test
    void should_identify_active_speaker_by_motion_during_speech() {
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(
                        sample(10.0, 300, 5.0),
                        sample(14.0, 300, 1.0))),
                new FaceTrack(2, List.of(
                        sample(10.0, 1500, 1.0),
                        sample(14.0, 1500, 6.0)))));

        List<SpeakerActivity> activities = tracker.track(faces, words(), 9.0, 16.0);

        assertThat(activities).containsExactly(
                new SpeakerActivity(10.0, 16.0, "1", 1.0));
    }

    @Test
    void should_switch_speaker_when_motion_and_speech_move() {
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(
                        sample(10.0, 300, 5.0),
                        sample(14.0, 300, 1.0))),
                new FaceTrack(2, List.of(
                        sample(10.0, 1500, 1.0),
                        sample(14.0, 1500, 6.0)))));

        List<Word> words = List.of(
                new Word("Hello", 10.0, 10.5),
                new Word("world", 10.5, 11.0),
                new Word("Yes", 13.8, 14.2),
                new Word("exactly", 14.2, 14.8));

        List<SpeakerActivity> activities = tracker.track(faces, words, 9.0, 16.0);

        assertThat(activities).containsExactly(
                new SpeakerActivity(10.0, 14.0, "1", 1.0),
                new SpeakerActivity(14.0, 16.0, "2", 1.0));
    }

    @Test
    void should_keep_previous_speaker_when_no_motion() {
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(
                        sample(10.0, 300, 5.0),
                        sample(14.0, 300, 0.0))),
                new FaceTrack(2, List.of(
                        sample(10.0, 1500, 1.0),
                        sample(14.0, 1500, 0.0)))));

        List<SpeakerActivity> activities = tracker.track(faces, words(), 9.0, 16.0);

        assertThat(activities).containsExactly(
                new SpeakerActivity(10.0, 16.0, "1", 1.0));
    }

    @Test
    void should_merge_short_segments() {
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(
                        sample(9.0, 300, 5.0),
                        sample(10.8, 300, 4.0),
                        sample(13.0, 300, 5.0))),
                new FaceTrack(2, List.of(
                        sample(9.0, 1500, 1.0),
                        sample(10.3, 1500, 8.0),
                        sample(13.0, 1500, 1.0)))));

        List<SpeakerActivity> activities = tracker.track(faces, words(), 9.0, 14.0);

        assertThat(activities).containsExactly(
                new SpeakerActivity(9.0, 14.0, "1", 1.0));
    }

    private List<Word> words() {
        return List.of(new Word("Hello", 10.0, 10.5), new Word("world", 10.5, 11.0));
    }

    private FaceSample sample(double t, double cx, double motion) {
        return new FaceSample(t, cx, 540, 200, 180, motion);
    }
}
