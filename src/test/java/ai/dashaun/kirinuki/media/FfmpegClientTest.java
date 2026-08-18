package ai.dashaun.kirinuki.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class FfmpegClientTest {

    private static final Path SOURCE = Path.of("/storage/video/source.mp4");
    private static final Path AUDIO = Path.of("/storage/video/audio.wav");
    private static final Path SUBTITLES = Path.of("/storage/video/clips/clip-1.ass");
    private static final Path TARGET = Path.of("/storage/video/clips/clip-1.mp4");

    private final ProcessRunner processRunner = mock(ProcessRunner.class);

    @Test
    void should_ask_ffmpeg_for_mono_sixteen_kilohertz_pcm_when_extracting_audio() {
        client(Map.of()).extractAudio(SOURCE, AUDIO);

        assertThat(command()).containsExactly("ffmpeg",
                "-y",
                "-i", SOURCE.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                AUDIO.toString());
    }

    @Test
    void should_call_the_configured_binary_with_the_media_timeout() {
        client(Map.of(
                "kirinuki.pipeline.media.binary", "/opt/ffmpeg/bin/ffmpeg",
                "kirinuki.pipeline.media.timeout", "45m")).extractAudio(SOURCE, AUDIO);

        verify(processRunner).run(eq("/opt/ffmpeg/bin/ffmpeg"), anyList(), eq(Duration.ofMinutes(45)));
    }

    @Test
    void should_read_the_cut_times_from_the_scene_metadata_output() {
        stubOutput("""
                frame:0    pts:15360   pts_time:0.32
                lavfi.scene_score=0.512000
                frame:1    pts:576000  pts_time:12.34
                lavfi.scene_score=0.874000
                """);

        assertThat(client(Map.of()).detectScenes(SOURCE)).containsExactly(0.32, 12.34);
    }

    @Test
    void should_report_no_cuts_when_ffmpeg_prints_only_banner_noise() {
        stubOutput("""
                Input #0, mov,mp4,m4a,3gp,3g2,mj2, from '/storage/video/source.mp4':
                  Duration: 00:14:02.13, start: 0.000000, bitrate: 1130 kb/s
                """);

        assertThat(client(Map.of()).detectScenes(SOURCE)).isEmpty();
    }

    @Test
    void should_pass_the_configured_threshold_into_the_scene_select_filter() {
        stubOutput("");

        client(Map.of("kirinuki.pipeline.scenes.threshold", 0.25)).detectScenes(SOURCE);

        assertThat(argumentAfter("-vf")).isEqualTo("select='gt(scene,0.25)',metadata=print:file=-");
    }

    @Test
    void should_decode_without_audio_when_detecting_scenes() {
        stubOutput("");

        client(Map.of()).detectScenes(SOURCE);

        assertThat(command()).contains("-an");
    }

    @Test
    void should_pair_each_silence_start_with_its_end() {
        stubOutput("""
                frame:0    pts:0       pts_time:0
                lavfi.silence_start=12.5
                frame:1    pts:16000   pts_time:1
                lavfi.silence_end=13.25
                lavfi.silence_duration=0.75
                """);

        assertThat(client(Map.of()).detectSilence(AUDIO)).containsExactly(new Silence(12.5, 13.25));
    }

    @Test
    void should_read_every_silence_in_the_order_ffmpeg_reported_them() {
        stubOutput("""
                lavfi.silence_start=1.5
                lavfi.silence_end=2.0
                lavfi.silence_start=30.25
                lavfi.silence_end=31.0
                lavfi.silence_start=95.0
                lavfi.silence_end=96.5
                """);

        assertThat(client(Map.of()).detectSilence(AUDIO)).containsExactly(
                new Silence(1.5, 2.0),
                new Silence(30.25, 31.0),
                new Silence(95.0, 96.5));
    }

    @Test
    void should_drop_a_silence_that_never_reports_an_end() {
        stubOutput("""
                lavfi.silence_start=1.5
                lavfi.silence_end=2.0
                lavfi.silence_start=840.0
                """);

        assertThat(client(Map.of()).detectSilence(AUDIO)).containsExactly(new Silence(1.5, 2.0));
    }

    @Test
    void should_ignore_an_end_marker_that_has_no_start() {
        stubOutput("""
                lavfi.silence_end=2.0
                lavfi.silence_start=30.25
                lavfi.silence_end=31.0
                """);

        assertThat(client(Map.of()).detectSilence(AUDIO)).containsExactly(new Silence(30.25, 31.0));
    }

    @Test
    void should_report_no_silence_when_ffmpeg_found_none() {
        stubOutput("size=N/A time=00:14:02.13 bitrate=N/A speed=  42x");

        assertThat(client(Map.of()).detectSilence(AUDIO)).isEmpty();
    }

    @Test
    void should_pass_the_configured_noise_floor_and_minimum_duration_into_the_silence_filter() {
        stubOutput("");

        client(Map.of(
                "kirinuki.pipeline.audio.silence-threshold", -35,
                "kirinuki.pipeline.audio.silence-min-duration", "750ms")).detectSilence(AUDIO);

        assertThat(argumentAfter("-af")).isEqualTo("silencedetect=noise=-35dB:d=0.75,ametadata=mode=print:file=-");
    }

    @Test
    void should_seek_before_the_input_so_ffmpeg_seeks_fast() {
        client(Map.of()).renderVertical(SOURCE, 92.5, 30.0, SUBTITLES, TARGET);

        List<String> command = command();
        assertThat(command.indexOf("-ss")).isLessThan(command.indexOf("-i"));
        assertThat(argumentAfter("-ss")).isEqualTo("92.5");
    }

    @Test
    void should_bound_the_clip_with_the_requested_duration() {
        client(Map.of()).renderVertical(SOURCE, 92.5, 30.0, SUBTITLES, TARGET);

        assertThat(argumentAfter("-t")).isEqualTo("30.0");
    }

    @Test
    void should_encode_with_the_configured_preset_and_quality() {
        client(Map.of(
                "kirinuki.pipeline.render.preset", "slow",
                "kirinuki.pipeline.render.crf", 19)).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(argumentAfter("-preset")).isEqualTo("slow");
        assertThat(argumentAfter("-crf")).isEqualTo("19");
    }

    @Test
    void should_use_the_render_timeout_rather_than_the_media_timeout() {
        client(Map.of(
                "kirinuki.pipeline.media.timeout", "45m",
                "kirinuki.pipeline.render.timeout", "10m")).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        verify(processRunner).run(anyString(), anyList(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void should_scale_the_blurred_backdrop_to_an_eighth_of_the_frame() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).contains("[bg]scale=135:240:force_original_aspect_ratio=increase,crop=135:240,"
                + "boxblur=8:2,scale=1080:1920[b]");
    }

    @Test
    void should_keep_the_blurred_backdrop_at_least_two_pixels_wide() {
        client(Map.of(
                "kirinuki.pipeline.render.width", 8,
                "kirinuki.pipeline.render.height", 8)).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).contains("[bg]scale=2:2:force_original_aspect_ratio=increase,crop=2:2,");
    }

    @Test
    void should_fill_the_frame_width_when_zoom_is_neutral() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).contains("[fg]scale=1080:-2,crop=1080:ih:(iw-1080)/2:0[f]");
    }

    @Test
    void should_round_the_zoomed_width_to_an_even_number_of_pixels() {
        client(Map.of("kirinuki.pipeline.render.zoom", 0.37)).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).contains("[fg]scale=400:-2,");
    }

    @Test
    void should_centre_the_video_over_the_backdrop() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).contains("[b][f]overlay=(W-w)/2:(H-h)/2[comp]");
    }

    @Test
    void should_lay_the_gradient_over_the_bottom_sixth_of_the_frame() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter())
                .contains("color=black:s=1080x320,format=rgba,geq=r=0:g=0:b=0:a='220*Y/H'[grad]")
                .contains("[comp][grad]overlay=0:1600:shortest=1,");
    }

    @Test
    void should_burn_the_subtitle_file_in_as_the_last_filter_step() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(filter()).endsWith("subtitles=" + SUBTITLES);
    }

    @Test
    void should_write_a_streamable_mp4_when_rendering_a_clip() {
        client(Map.of()).renderVertical(SOURCE, 0.0, 30.0, SUBTITLES, TARGET);

        assertThat(command())
                .containsSequence("-c:v", "libx264")
                .containsSequence("-c:a", "aac")
                .containsSequence("-movflags", "+faststart")
                .endsWith(TARGET.toString());
    }

    private FfmpegClient client(Map<String, Object> configuration) {
        return new FfmpegClient(new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class), processRunner);
    }

    private void stubOutput(String output) {
        when(processRunner.run(anyString(), anyList(), any(Duration.class))).thenReturn(output);
    }

    private List<String> command() {
        ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
        verify(processRunner).run(anyString(), command.capture(), any(Duration.class));
        return command.getValue();
    }

    private String argumentAfter(String flag) {
        List<String> command = command();
        return command.get(command.indexOf(flag) + 1);
    }

    private String filter() {
        return argumentAfter("-filter_complex");
    }
}
