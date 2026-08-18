package ai.dashaun.kirinuki.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CandidateScorerTest {

    private static final String VIDEO_TITLE = "Spring Boot 4 in anger";

    private final ObjectMapper objectMapper = new JsonMapper();
    private final CandidateScoreClient scoreClient = mock(CandidateScoreClient.class);

    @TempDir
    Path directory;

    @Test
    void should_use_the_configured_weights_when_computing_the_overall_score() throws IOException {
        Candidate candidate = candidate(0, 0.0, 30.0);
        when(scoreClient.score(candidate, VIDEO_TITLE)).thenReturn(score(10, 0, 0, 0, 0));

        List<ScoredCandidate> scored = score(List.of(candidate), Map.of());

        assertThat(scored).singleElement().extracting(ScoredCandidate::overallScore).isEqualTo(30);
    }

    @Test
    void should_reach_one_hundred_when_every_sub_score_is_full() throws IOException {
        Candidate candidate = candidate(0, 0.0, 30.0);
        when(scoreClient.score(candidate, VIDEO_TITLE)).thenReturn(score(10, 10, 10, 10, 10));

        List<ScoredCandidate> scored = score(List.of(candidate), Map.of());

        assertThat(scored).singleElement().extracting(ScoredCandidate::overallScore).isEqualTo(100);
    }

    @Test
    void should_clamp_sub_scores_before_weighting_them() throws IOException {
        Candidate candidate = candidate(0, 0.0, 30.0);
        when(scoreClient.score(candidate, VIDEO_TITLE)).thenReturn(score(99, 0, 0, 0, 0));

        List<ScoredCandidate> scored = score(List.of(candidate), Map.of());

        assertThat(scored).singleElement().extracting(ScoredCandidate::overallScore).isEqualTo(30);
    }

    @Test
    void should_honour_reweighted_dimensions_when_the_configuration_changes() throws IOException {
        Candidate candidate = candidate(0, 0.0, 30.0);
        when(scoreClient.score(candidate, VIDEO_TITLE)).thenReturn(score(10, 0, 0, 0, 0));

        List<ScoredCandidate> scored = score(List.of(candidate), Map.of(
                "kirinuki.pipeline.scoring.weights.hook", 1,
                "kirinuki.pipeline.scoring.weights.educational-value", 1,
                "kirinuki.pipeline.scoring.weights.emotion", 1,
                "kirinuki.pipeline.scoring.weights.visual-interest", 1,
                "kirinuki.pipeline.scoring.weights.virality", 1));

        assertThat(scored).singleElement().extracting(ScoredCandidate::overallScore).isEqualTo(20);
    }

    @Test
    void should_drop_a_candidate_that_scores_below_the_minimum() throws IOException {
        Candidate weak = candidate(0, 0.0, 30.0);
        Candidate strong = candidate(1, 100.0, 130.0);
        when(scoreClient.score(weak, VIDEO_TITLE)).thenReturn(score(3, 3, 3, 3, 3));
        when(scoreClient.score(strong, VIDEO_TITLE)).thenReturn(score(9, 9, 9, 9, 9));

        List<ScoredCandidate> scored = score(List.of(weak, strong),
                Map.of("kirinuki.pipeline.scoring.min-score", 50));

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(strong);
    }

    @Test
    void should_rank_the_kept_candidates_from_best_to_worst() throws IOException {
        Candidate middle = candidate(0, 0.0, 30.0);
        Candidate best = candidate(1, 100.0, 130.0);
        Candidate worst = candidate(2, 200.0, 230.0);
        when(scoreClient.score(middle, VIDEO_TITLE)).thenReturn(score(5, 5, 5, 5, 5));
        when(scoreClient.score(best, VIDEO_TITLE)).thenReturn(score(9, 9, 9, 9, 9));
        when(scoreClient.score(worst, VIDEO_TITLE)).thenReturn(score(1, 1, 1, 1, 1));

        List<ScoredCandidate> scored = score(List.of(middle, best, worst), Map.of());

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(best, middle, worst);
    }

    @Test
    void should_cut_the_ranking_to_the_configured_number_of_clips() throws IOException {
        Candidate first = candidate(0, 0.0, 30.0);
        Candidate second = candidate(1, 100.0, 130.0);
        Candidate third = candidate(2, 200.0, 230.0);
        when(scoreClient.score(first, VIDEO_TITLE)).thenReturn(score(9, 9, 9, 9, 9));
        when(scoreClient.score(second, VIDEO_TITLE)).thenReturn(score(7, 7, 7, 7, 7));
        when(scoreClient.score(third, VIDEO_TITLE)).thenReturn(score(5, 5, 5, 5, 5));

        List<ScoredCandidate> scored = score(List.of(first, second, third),
                Map.of("kirinuki.pipeline.scoring.top-clips", 2));

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(first, second);
    }

    @Test
    void should_keep_only_the_stronger_candidate_when_two_windows_mostly_overlap() throws IOException {
        Candidate shorter = candidate(0, 10.0, 40.0);
        Candidate longer = candidate(1, 20.0, 50.0);
        when(scoreClient.score(shorter, VIDEO_TITLE)).thenReturn(score(4, 4, 4, 4, 4));
        when(scoreClient.score(longer, VIDEO_TITLE)).thenReturn(score(8, 8, 8, 8, 8));

        List<ScoredCandidate> scored = score(List.of(shorter, longer), Map.of());

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(longer);
    }

    @Test
    void should_keep_both_candidates_when_they_overlap_by_no_more_than_half() throws IOException {
        Candidate first = candidate(0, 0.0, 30.0);
        Candidate second = candidate(1, 20.0, 50.0);
        when(scoreClient.score(first, VIDEO_TITLE)).thenReturn(score(8, 8, 8, 8, 8));
        when(scoreClient.score(second, VIDEO_TITLE)).thenReturn(score(4, 4, 4, 4, 4));

        List<ScoredCandidate> scored = score(List.of(first, second), Map.of());

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(first, second);
    }

    @Test
    void should_keep_adjacent_candidates_that_only_touch_at_the_boundary() throws IOException {
        Candidate first = candidate(0, 0.0, 30.0);
        Candidate second = candidate(1, 30.0, 60.0);
        when(scoreClient.score(first, VIDEO_TITLE)).thenReturn(score(8, 8, 8, 8, 8));
        when(scoreClient.score(second, VIDEO_TITLE)).thenReturn(score(4, 4, 4, 4, 4));

        List<ScoredCandidate> scored = score(List.of(first, second), Map.of());

        assertThat(scored).hasSize(2);
    }

    @Test
    void should_skip_a_candidate_when_the_model_call_fails() throws IOException {
        Candidate failing = candidate(0, 0.0, 30.0);
        Candidate scoring = candidate(1, 100.0, 130.0);
        when(scoreClient.score(failing, VIDEO_TITLE)).thenThrow(new KirinukiException("model exploded"));
        when(scoreClient.score(scoring, VIDEO_TITLE)).thenReturn(score(5, 5, 5, 5, 5));

        List<ScoredCandidate> scored = score(List.of(failing, scoring), Map.of());

        assertThat(scored).extracting(ScoredCandidate::candidate).containsExactly(scoring);
    }

    @Test
    void should_fail_when_every_candidate_fails_scoring() throws IOException {
        Path candidates = writeCandidates(List.of(candidate(0, 0.0, 30.0), candidate(1, 100.0, 130.0)));
        when(scoreClient.score(any(), eq(VIDEO_TITLE))).thenThrow(new KirinukiException("model unavailable"));
        CandidateScorer scorer = new CandidateScorer(properties(Map.of()), objectMapper, scoreClient);

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> scorer.score(candidates, directory.resolve("scored.json"), VIDEO_TITLE))
                .withMessageContaining("Every candidate failed scoring");
    }

    @Test
    void should_write_an_empty_ranking_when_there_are_no_candidates() throws IOException {
        List<ScoredCandidate> scored = score(List.of(), Map.of());

        assertThat(scored).isEmpty();
    }

    @Test
    void should_pass_the_video_title_to_the_model_with_each_candidate() throws IOException {
        Candidate candidate = candidate(0, 0.0, 30.0);
        when(scoreClient.score(candidate, VIDEO_TITLE)).thenReturn(score(5, 5, 5, 5, 5));

        score(List.of(candidate), Map.of());

        verify(scoreClient).score(candidate, VIDEO_TITLE);
    }

    @Test
    void should_fail_when_the_candidates_file_is_missing() {
        CandidateScorer scorer = new CandidateScorer(properties(Map.of()), objectMapper, scoreClient);

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> scorer.score(directory.resolve("absent.json"), directory.resolve("scored.json"),
                        VIDEO_TITLE))
                .withMessageContaining("Could not read candidates");
    }

    private List<ScoredCandidate> score(List<Candidate> candidates, Map<String, Object> configuration)
            throws IOException {
        Path target = directory.resolve("scored.json");
        new CandidateScorer(properties(configuration), objectMapper, scoreClient)
                .score(writeCandidates(candidates), target, VIDEO_TITLE);
        return objectMapper.readValue(Files.readString(target), new TypeReference<List<ScoredCandidate>>() {
        });
    }

    private Path writeCandidates(List<Candidate> candidates) throws IOException {
        return Files.writeString(directory.resolve("candidates.json"), objectMapper.writeValueAsString(candidates));
    }

    private Candidate candidate(int id, double start, double end) {
        return new Candidate(id, start, end, id * 10, id * 10 + 9, "candidate " + id);
    }

    private CandidateScore score(int hook, int educationalValue, int emotion, int visualInterest, int virality) {
        return new CandidateScore(hook, educationalValue, emotion, visualInterest, virality, List.of("TikTok"),
                "because");
    }

    private KirinukiPipelineProperties properties(Map<String, Object> configuration) {
        return new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
