package ai.dashaun.kirinuki.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CandidateScoreTest {

    @Test
    void should_pull_sub_scores_up_to_zero_when_the_model_returns_negatives() {
        CandidateScore clamped = new CandidateScore(-3, -1, 0, 5, -99, List.of("TikTok"), "why").clamped();

        assertThat(clamped).isEqualTo(new CandidateScore(0, 0, 0, 5, 0, List.of("TikTok"), "why"));
    }

    @Test
    void should_pull_sub_scores_down_to_ten_when_the_model_returns_more_than_ten() {
        CandidateScore clamped = new CandidateScore(11, 100, 10, 7, 42, List.of(), "why").clamped();

        assertThat(clamped).isEqualTo(new CandidateScore(10, 10, 10, 7, 10, List.of(), "why"));
    }

    @Test
    void should_leave_sub_scores_alone_when_they_are_already_in_range() {
        CandidateScore score = new CandidateScore(0, 3, 5, 8, 10, List.of("X"), "why");

        assertThat(score.clamped()).isEqualTo(score);
    }
}
