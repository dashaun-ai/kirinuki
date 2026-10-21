package ai.dashaun.kirinuki.vision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.MediaProfile;
import ai.dashaun.kirinuki.media.SpeakerSegment;

class RoiPlannerTest {

    private static final MediaProfile PROFILE = new MediaProfile(1920, 1080, 30.0, 120.0, true);

    private final DominantSpeakerSelector selector = new DominantSpeakerSelector();
    private final RoiPlanner planner = new RoiPlanner(selector);

    @Test
    void should_frame_code_region_when_screen_has_code() {
        ScreenAnalysis screen = new ScreenAnalysis(new ScreenRegion(0.25, 0.2, 0.5, 0.5), List.of(), true);

        Optional<FramingPlan> plan = planner.plan(screen, FaceTracks.empty(), List.of(), 0.0, 10.0, PROFILE,
                properties("content-aware"));

        assertThat(plan).hasValueSatisfying(framing -> {
            assertThat(framing.width()).isGreaterThan(0);
            assertThat(framing.height()).isGreaterThan(0);
            assertThat(framing.segments()).containsExactly(new SpeakerSegment(0.0, 10.0, framing.segments().get(0).x(),
                    framing.segments().get(0).y()));
        });
    }

    @Test
    void should_fall_back_to_speaker_plan_when_screen_is_missing_and_faces_are_present() {
        List<Word> words = List.of(new Word("Hello", 1.0, 1.5));
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(new FaceSample(1.2, 500, 500, 200, 180, 5.0)))));

        Optional<FramingPlan> plan = planner.plan(ScreenAnalysis.empty(), faces, words, 0.0, 10.0, PROFILE,
                properties("active-speaker"));

        assertThat(plan).isPresent();
    }

    @Test
    void should_return_empty_when_screen_is_missing_and_no_faces_are_present() {
        Optional<FramingPlan> plan = planner.plan(ScreenAnalysis.empty(), FaceTracks.empty(), List.of(), 0.0, 10.0,
                PROFILE, properties("content-aware"));

        assertThat(plan).isEmpty();
    }

    @Test
    void should_fall_back_to_speaker_plan_for_content_aware_when_no_code_is_present() {
        List<Word> words = List.of(new Word("Hello", 1.0, 1.5));
        FaceTracks faces = new FaceTracks(1920, 1080, List.of(
                new FaceTrack(1, List.of(new FaceSample(1.2, 500, 500, 200, 180, 5.0)))));

        Optional<FramingPlan> plan = planner.plan(ScreenAnalysis.empty(), faces, words, 0.0, 10.0, PROFILE,
                properties("content-aware"));

        assertThat(plan).isPresent();
    }

    private KirinukiPipelineProperties properties(String framing) {
        return new Binder(new MapConfigurationPropertySource(Map.of("kirinuki.pipeline.render.framing", framing)))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
