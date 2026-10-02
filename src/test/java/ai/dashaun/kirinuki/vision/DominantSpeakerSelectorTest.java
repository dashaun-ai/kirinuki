package ai.dashaun.kirinuki.vision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.Region;
import ai.dashaun.kirinuki.media.SpeakerSegment;

class DominantSpeakerSelectorTest {

    private static final KirinukiPipelineProperties.Render.Reframe REFRAME = reframe();

    private final DominantSpeakerSelector selector = new DominantSpeakerSelector();

    @Test
    void should_pick_the_speaker_with_the_most_mouth_motion_during_speech() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(10.0, 250, 550, 200, 5.0))),
                new FaceTrack(2, List.of(sample(10.0, 1450, 550, 200, 1.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(0, 213, 600, 750));
    }

    @Test
    void should_clamp_the_region_into_the_frame_when_the_speaker_is_at_the_right_edge() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(10.0, 1850, 550, 200, 5.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(1320, 213, 600, 750));
    }

    @Test
    void should_return_empty_when_there_is_no_speech_in_the_clip() {
        List<FaceTrack> tracks = List.of(new FaceTrack(1, List.of(sample(10.0, 250, 550, 200, 5.0))));

        Optional<Region> region = selector.select(tracks, List.of(new Word("Hello", 100.0, 101.0)),
                9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).isEmpty();
    }

    @Test
    void should_return_empty_when_no_track_has_samples_in_the_clip() {
        List<FaceTrack> tracks = List.of(new FaceTrack(1, List.of(sample(100.0, 250, 550, 200, 5.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).isEmpty();
    }

    @Test
    void should_fall_back_to_total_motion_when_no_sample_lands_near_speech() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(6.0, 250, 550, 200, 5.0))),
                new FaceTrack(2, List.of(sample(6.0, 1450, 550, 200, 1.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(0, 213, 600, 750));
    }

    @Test
    void should_aggregate_motion_across_fragmented_tracks_of_the_same_speaker() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(10.0, 500, 550, 200, 3.0))),
                new FaceTrack(2, List.of(sample(10.0, 510, 550, 200, 3.0))),
                new FaceTrack(3, List.of(sample(10.0, 1450, 550, 200, 4.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(205, 213, 600, 750));
    }

    @Test
    void should_pick_the_dominant_speaker_among_three_speakers() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(10.0, 300, 550, 200, 2.0))),
                new FaceTrack(2, List.of(sample(10.0, 960, 550, 200, 3.0))),
                new FaceTrack(3, List.of(sample(10.0, 1600, 550, 200, 7.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(1300, 213, 600, 750));
    }

    @Test
    void should_pick_the_dominant_speaker_among_four_speakers() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(sample(10.0, 240, 550, 200, 1.0))),
                new FaceTrack(2, List.of(sample(10.0, 720, 550, 200, 2.0))),
                new FaceTrack(3, List.of(sample(10.0, 1200, 550, 200, 3.0))),
                new FaceTrack(4, List.of(sample(10.0, 1680, 550, 200, 8.0))));

        Optional<Region> region = selector.select(tracks, words(), 9.0, 13.0, 1920, 1080, REFRAME);

        assertThat(region).contains(new Region(1320, 213, 600, 750));
    }

    @Test
    void should_split_the_clip_into_segments_for_each_active_speaker() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(
                        sample(9.0, 300, 550, 200, 5.0),
                        sample(14.0, 300, 550, 200, 4.0),
                        sample(19.0, 300, 550, 200, 1.0),
                        sample(24.0, 300, 550, 200, 1.0))),
                new FaceTrack(2, List.of(
                        sample(9.0, 1500, 550, 200, 1.0),
                        sample(14.0, 1500, 550, 200, 2.0),
                        sample(19.0, 1500, 550, 200, 6.0),
                        sample(24.0, 1500, 550, 200, 7.0))));

        Optional<FramingPlan> plan = selector.plan(tracks, words(), 9.0, 29.0, 1920, 1080, REFRAME);

        assertThat(plan).hasValueSatisfying(framing -> assertThat(framing.segments()).containsExactly(
                new SpeakerSegment(9.0, 19.0, 0, 213),
                new SpeakerSegment(19.0, 29.0, 1200, 213)));
    }

    @Test
    void should_merge_short_segments_into_their_neighbor() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(
                        sample(9.0, 300, 550, 200, 5.0),
                        sample(14.0, 300, 550, 200, 3.0),
                        sample(19.0, 300, 550, 200, 4.0),
                        sample(24.0, 300, 550, 200, 3.0))),
                new FaceTrack(2, List.of(
                        sample(9.0, 1500, 550, 200, 1.0),
                        sample(14.0, 1500, 550, 200, 6.0),
                        sample(19.0, 1500, 550, 200, 1.0),
                        sample(24.0, 1500, 550, 200, 1.0))));

        Optional<FramingPlan> plan = selector.plan(tracks, words(), 9.0, 29.0, 1920, 1080, REFRAME);

        assertThat(plan).hasValueSatisfying(framing -> assertThat(framing.segments()).containsExactly(
                new SpeakerSegment(9.0, 19.0, 1200, 213),
                new SpeakerSegment(19.0, 29.0, 0, 213)));
    }

    @Test
    void should_return_a_single_segment_when_one_speaker_dominates() {
        List<FaceTrack> tracks = List.of(
                new FaceTrack(1, List.of(
                        sample(9.0, 300, 550, 200, 5.0),
                        sample(14.0, 300, 550, 200, 4.0),
                        sample(19.0, 300, 550, 200, 3.0),
                        sample(24.0, 300, 550, 200, 2.0))),
                new FaceTrack(2, List.of(
                        sample(9.0, 1500, 550, 200, 1.0),
                        sample(14.0, 1500, 550, 200, 1.0),
                        sample(19.0, 1500, 550, 200, 1.0),
                        sample(24.0, 1500, 550, 200, 1.0))));

        Optional<FramingPlan> plan = selector.plan(tracks, words(), 9.0, 29.0, 1920, 1080, REFRAME);

        assertThat(plan).hasValueSatisfying(framing -> assertThat(framing.segments()).containsExactly(
                new SpeakerSegment(9.0, 29.0, 0, 213)));
    }

    private List<Word> words() {
        return List.of(new Word("Hello", 10.0, 10.5), new Word("world", 10.5, 11.0));
    }

    private FaceSample sample(double t, double cx, double cy, double width, double motion) {
        return new FaceSample(t, cx, cy, width, width * 0.9, motion);
    }

    private static KirinukiPipelineProperties.Render.Reframe reframe() {
        return new Binder(new MapConfigurationPropertySource(java.util.Map.of()))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class)
                .render().reframe();
    }
}
