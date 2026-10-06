package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class EditPlanValidatorTest {

    private final KirinukiPipelineProperties properties = new KirinukiPipelineProperties(
            null, null, null, null, null, null, null, null, null,
            new KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest", 0.4, 2048, 0.8, 6, 12, 0.6, null, null, null));

    private final EditPlanValidator validator = new EditPlanValidator(properties);

    @Test
    void should_fill_gap_with_source_when_shots_do_not_touch() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.SLAM, "Virtual threads", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 8, List.of());

        assertThat(result.shots().get(0).block()).isEqualTo(ShotBlock.SLAM);
        assertThat(result.shots().get(1).block()).isEqualTo(ShotBlock.SOURCE);
        assertThat(result.shots().get(2).block()).isEqualTo(ShotBlock.OUTRO);
        assertThat(result.shots().get(1).in()).isEqualTo(2);
        assertThat(result.shots().get(1).out()).isGreaterThan(2);
    }

    @Test
    void should_clamp_shots_to_clip_duration() {
        EditPlan plan = plan(shot(-1, 7, ShotBlock.SLAM, "Text", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots()).allMatch(s -> s.in() >= 0 && s.out() <= 5);
        assertThat(result.shots().get(0).in()).isEqualTo(0);
    }

    @Test
    void should_trim_overlaps_so_earlier_shot_wins() {
        EditPlan plan = plan(
                shot(0, 3, ShotBlock.SLAM, "One", null, null, null, null),
                shot(2, 4, ShotBlock.HIGHLIGHT, "Two", "sub", null, null, null),
                shot(4, 10, ShotBlock.SOURCE, null, null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 10, List.of());

        assertThat(result.shots().get(0).block()).isEqualTo(ShotBlock.SLAM);
        assertThat(result.shots().get(0).out()).isEqualTo(3);
        assertThat(result.shots().get(1).block()).isEqualTo(ShotBlock.HIGHLIGHT);
        assertThat(result.shots().get(1).in()).isEqualTo(3);
    }

    @Test
    void should_drop_shots_shorter_than_minimum_duration() {
        EditPlan plan = plan(shot(0, 0.5, ShotBlock.SLAM, "Too short", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots()).allMatch(s -> s.block() != ShotBlock.SLAM);
    }

    @Test
    void should_split_source_shots_longer_than_max_source_seconds() {
        EditPlan plan = plan(shot(0, 15, ShotBlock.SOURCE, null, null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 15, List.of());

        List<Shot> sourceShots = result.shots().stream().filter(s -> s.block() == ShotBlock.SOURCE).toList();
        assertThat(sourceShots).allMatch(s -> s.out() - s.in() <= 12);
    }

    @Test
    void should_convert_non_source_shots_longer_than_max_shot_seconds() {
        EditPlan plan = plan(shot(0, 8, ShotBlock.SLAM, "Long", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 8, List.of());

        assertThat(result.shots().get(0).out() - result.shots().get(0).in()).isLessThanOrEqualTo(6);
    }

    @Test
    void should_cap_non_source_coverage_at_max_designed_ratio() {
        EditPlan plan = plan(
                shot(0, 3, ShotBlock.SLAM, "A", null, null, null, null),
                shot(3, 6, ShotBlock.HIGHLIGHT, "B", "sub", null, null, null),
                shot(6, 10, ShotBlock.SOURCE, null, null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 10, List.of());

        double designed = result.shots().stream()
                .filter(s -> s.block() != ShotBlock.SOURCE)
                .filter(s -> s.block() != ShotBlock.OUTRO)
                .mapToDouble(s -> s.out() - s.in())
                .sum();
        assertThat(designed / 10).isLessThanOrEqualTo(0.6);
    }

    @Test
    void should_repair_slam_with_too_many_words_to_source() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.SLAM, "This is way too many words", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots().get(0).block()).isEqualTo(ShotBlock.SOURCE);
    }

    @Test
    void should_repair_highlight_missing_subline_to_source() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.HIGHLIGHT, "Headline", null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots().get(0).block()).isEqualTo(ShotBlock.SOURCE);
    }

    @Test
    void should_filter_logos_to_bundled_library() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.LOGO_WALL, null, null, null,
                List.of("spring", "unknown-logo", "docker"), null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots().get(0).logos()).containsExactly("spring", "docker");
    }

    @Test
    void should_convert_logo_wall_with_no_known_logos_to_source() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.LOGO_WALL, null, null, null, List.of("unknown"), null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots().get(0).block()).isEqualTo(ShotBlock.SOURCE);
    }

    @Test
    void should_force_last_shot_to_outro() {
        EditPlan plan = plan(shot(0, 2, ShotBlock.SOURCE, null, null, null, null, null));

        EditPlan result = validator.validateAndRepair(plan, 5, List.of());

        assertThat(result.shots().getLast().block()).isEqualTo(ShotBlock.OUTRO);
    }

    @Test
    void should_snap_emphasis_to_nearest_word_start() {
        EditPlan plan = new EditPlan(1, "spring", List.of(shot(0, 5, ShotBlock.SOURCE, null, null, null, null, null)),
                List.of(new Emphasis("virtual", 1.2, EmphasisKind.PUNCH)));
        List<Word> words = List.of(new Word("hello", 0.5, 0.9), new Word("virtual", 1.4, 1.8));

        EditPlan result = validator.validateAndRepair(plan, 5, words);

        assertThat(result.emphasis()).hasSize(1);
        assertThat(result.emphasis().getFirst().at()).isEqualTo(1.4);
    }

    @Test
    void should_fail_when_repair_leaves_no_shots() {
        EditPlan plan = plan();

        assertThatThrownBy(() -> validator.validateAndRepair(plan, 0, List.of()))
                .isInstanceOf(InvalidEditPlanException.class);
    }

    private EditPlan plan(Shot... shots) {
        return new EditPlan(1, "spring", List.of(shots), List.of());
    }

    private Shot shot(double in, double out, ShotBlock block, String headline, String subline, List<String> lines,
            List<String> logos, String imageQuery) {
        return new Shot(in, out, block, headline, subline, lines, logos, imageQuery, null, null);
    }
}
