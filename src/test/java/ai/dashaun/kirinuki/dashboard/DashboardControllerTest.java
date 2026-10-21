package ai.dashaun.kirinuki.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Edit.Renderer;
import ai.dashaun.kirinuki.edit.DesignedEditService;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.review.ReviewService;
import ai.dashaun.kirinuki.video.VideoResponse;
import ai.dashaun.kirinuki.video.VideoService;

class DashboardControllerTest {

    private final VideoService videoService = mock(VideoService.class);
    private final ReviewService reviewService = mock(ReviewService.class);
    private final DesignedEditService designedEditService = mock(DesignedEditService.class);
    private final KirinukiPipelineProperties properties = mock(KirinukiPipelineProperties.class);

    private final DashboardController controller = new DashboardController(videoService, reviewService,
            designedEditService, properties);

    private final UUID videoId = UUID.randomUUID();

    @Test
    void dashboard_should_expose_edit_renderer_and_multi_speaker_flags() {
        VideoResponse video = new VideoResponse(videoId, "id", "url", "Title", 120, "uploader",
                PipelineStatus.READY_FOR_REVIEW, null, null);
        when(videoService.findById(videoId)).thenReturn(video);
        when(reviewService.review(videoId)).thenReturn(List.of());
        when(properties.edit()).thenReturn(editConfig(Renderer.KINETIC, false));
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.dashboard(videoId, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.get("editEnabled")).isEqualTo(true);
        assertThat(model.get("editRenderer")).isEqualTo("KINETIC");
        assertThat(model.get("editMultiSpeaker")).isEqualTo(false);
    }

    @Test
    void render_designed_should_call_service_render() {
        String result = controller.renderDesigned(videoId, 2);

        assertThat(result).isEqualTo("redirect:/videos/" + videoId + "/dashboard");
        verify(designedEditService).render(videoId, 2);
    }

    @Test
    void re_render_designed_should_call_service_force_render() {
        String result = controller.reRenderDesigned(videoId, 3);

        assertThat(result).isEqualTo("redirect:/videos/" + videoId + "/dashboard");
        verify(designedEditService).forceRender(videoId, 3);
    }

    private KirinukiPipelineProperties.Edit editConfig(Renderer renderer, boolean multiSpeaker) {
        return new KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest", 0.4, 2048, 0.8, 6, 12,
                0.6, null, null, null, null, null, null, renderer, multiSpeaker, Duration.ofMinutes(30));
    }
}
