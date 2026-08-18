package ai.dashaun.kirinuki.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.Silence;

class BoundaryRefinerTest {

    private static final double TOLERANCE = 1e-9;

    private static final List<Word> WORDS = List.of(
            new Word(" before", 8.0, 9.0),
            new Word(" first", 10.0, 12.0),
            new Word(" last", 18.0, 20.0),
            new Word(" after", 20.9, 21.4));

    private static final Candidate CANDIDATE = new Candidate(0, 10.0, 20.0, 1, 2, "first last");

    private final BoundaryRefiner boundaryRefiner = new BoundaryRefiner(properties(Map.of()));

    @Test
    void should_open_on_the_nearby_pause_when_a_silence_ends_next_to_the_candidate_start() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(9.2, 9.8)),
                List.of());

        assertThat(bounds.start()).isEqualTo(9.6, within(TOLERANCE));
    }

    @Test
    void should_not_reach_further_back_than_the_lead_in_when_the_pause_is_long() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(4.0, 9.9)),
                List.of());

        assertThat(bounds.start()).isEqualTo(9.6, within(TOLERANCE));
    }

    @Test
    void should_open_on_the_pause_start_when_the_pause_is_shorter_than_the_lead_in() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(9.8, 9.9)),
                List.of());

        assertThat(bounds.start()).isEqualTo(9.8, within(TOLERANCE));
    }

    @Test
    void should_pick_the_closest_pause_when_several_are_within_tolerance() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS,
                List.of(new Silence(8.5, 9.0), new Silence(9.8, 9.9)), List.of());

        assertThat(bounds.start()).isEqualTo(9.8, within(TOLERANCE));
    }

    @Test
    void should_ignore_a_pause_that_sits_outside_the_snap_tolerance() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(6.0, 8.5)),
                List.of());

        assertThat(bounds.start()).isEqualTo(9.6, within(TOLERANCE));
    }

    @Test
    void should_open_on_a_scene_cut_when_there_is_no_pause_to_snap_to() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(), List.of(9.5));

        assertThat(bounds.start()).isEqualTo(9.5, within(TOLERANCE));
    }

    @Test
    void should_prefer_a_pause_over_a_scene_cut_when_both_are_within_tolerance() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(9.8, 9.9)),
                List.of(9.5));

        assertThat(bounds.start()).isEqualTo(9.8, within(TOLERANCE));
    }

    @Test
    void should_limit_the_lead_in_to_the_real_gap_before_the_first_word() {
        List<Word> tightWords = List.of(
                new Word(" before", 8.0, 9.9),
                new Word(" first", 10.0, 12.0),
                new Word(" last", 18.0, 20.0));

        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, tightWords, List.of(), List.of());

        assertThat(bounds.start()).isEqualTo(9.9, within(TOLERANCE));
    }

    @Test
    void should_never_open_before_zero_when_the_candidate_starts_at_the_very_beginning() {
        Candidate opening = new Candidate(0, 0.2, 5.0, 0, 1, "opening line");
        List<Word> words = List.of(new Word(" opening", 0.2, 1.0), new Word(" line", 1.0, 5.0));

        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(opening, words, List.of(), List.of());

        assertThat(bounds.start()).isZero();
    }

    @Test
    void should_close_on_the_next_pause_but_keep_at_most_the_tail() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(20.3, 21.5)),
                List.of());

        assertThat(bounds.end()).isEqualTo(20.9, within(TOLERANCE));
    }

    @Test
    void should_close_on_the_pause_end_when_the_pause_is_shorter_than_the_tail() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(20.3, 20.4)),
                List.of());

        assertThat(bounds.end()).isEqualTo(20.4, within(TOLERANCE));
    }

    @Test
    void should_ignore_a_pause_that_starts_before_the_candidate_ends() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(new Silence(19.0, 19.5)),
                List.of());

        assertThat(bounds.end()).isEqualTo(20.6, within(TOLERANCE));
    }

    @Test
    void should_close_on_a_scene_cut_when_there_is_no_pause_after_the_candidate() {
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, WORDS, List.of(), List.of(20.5));

        assertThat(bounds.end()).isEqualTo(20.5, within(TOLERANCE));
    }

    @Test
    void should_limit_the_tail_to_the_real_gap_after_the_last_word() {
        List<Word> tightWords = List.of(
                new Word(" before", 8.0, 9.0),
                new Word(" first", 10.0, 12.0),
                new Word(" last", 18.0, 20.0),
                new Word(" after", 20.2, 21.0));

        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(CANDIDATE, tightWords, List.of(), List.of());

        assertThat(bounds.end()).isEqualTo(20.2, within(TOLERANCE));
    }

    @Test
    void should_add_the_whole_tail_when_the_candidate_ends_on_the_last_word() {
        Candidate closing = new Candidate(0, 10.0, 20.0, 1, 3, "first last after");

        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(closing, WORDS.subList(0, 4), List.of(), List.of());

        assertThat(bounds.end()).isEqualTo(20.6, within(TOLERANCE));
    }

    private KirinukiPipelineProperties properties(Map<String, Object> configuration) {
        return new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
