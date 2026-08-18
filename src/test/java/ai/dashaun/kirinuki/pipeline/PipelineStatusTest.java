package ai.dashaun.kirinuki.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class PipelineStatusTest {

    @Test
    void should_visit_every_stage_in_pipeline_order_when_next_is_followed_from_the_start() {
        List<PipelineStatus> walk = new ArrayList<>();
        PipelineStatus status = PipelineStatus.DOWNLOADING;
        walk.add(status);
        while (status.next() != status) {
            status = status.next();
            walk.add(status);
        }

        assertThat(walk).containsExactly(
                PipelineStatus.DOWNLOADING,
                PipelineStatus.MEDIA_PREPARATION,
                PipelineStatus.FEATURE_EXTRACTION,
                PipelineStatus.CANDIDATE_GENERATION,
                PipelineStatus.AI_ANALYSIS,
                PipelineStatus.CLIP_RENDERING,
                PipelineStatus.CONTENT_GENERATION,
                PipelineStatus.READY_FOR_REVIEW,
                PipelineStatus.READY_TO_PUBLISH,
                PipelineStatus.PUBLISHED);
    }

    @Test
    void should_stay_put_when_next_is_called_on_the_terminal_status() {
        assertThat(PipelineStatus.PUBLISHED.next()).isEqualTo(PipelineStatus.PUBLISHED);
    }

    @Test
    void should_not_be_resumable_when_the_status_waits_on_a_human() {
        assertThat(PipelineStatus.READY_FOR_REVIEW.isResumable()).isFalse();
        assertThat(PipelineStatus.PUBLISHED.isResumable()).isFalse();
    }

    @Test
    void should_be_resumable_when_the_status_is_a_machine_stage() {
        List<PipelineStatus> machineStages = Arrays.stream(PipelineStatus.values())
                .filter(status -> status != PipelineStatus.READY_FOR_REVIEW && status != PipelineStatus.PUBLISHED)
                .toList();

        assertThat(machineStages).allMatch(PipelineStatus::isResumable);
    }
}
