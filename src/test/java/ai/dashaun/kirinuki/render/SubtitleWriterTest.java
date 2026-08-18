package ai.dashaun.kirinuki.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class SubtitleWriterTest {

    private static final List<Word> WORDS = List.of(
            new Word(" Hello", 0.0, 0.5),
            new Word(" world.", 0.5, 1.0),
            new Word(" Next", 5.0, 5.5),
            new Word(" bit", 5.5, 6.0));

    @TempDir
    Path directory;

    @Test
    void should_write_one_dialogue_line_per_word_group() throws IOException {
        String subtitles = write(WORDS, 0.4, 5.7, Map.of("kirinuki.pipeline.render.words-per-caption", 2));

        assertThat(subtitles.lines().filter(line -> line.startsWith("Dialogue:"))).hasSize(2);
    }

    @Test
    void should_rebase_caption_timings_onto_the_clip_start() throws IOException {
        String subtitles = write(WORDS, 0.4, 5.7, Map.of("kirinuki.pipeline.render.words-per-caption", 2));

        assertThat(subtitles).contains("Dialogue: 0,0:00:04.60,0:00:05.30,Default,,0,0,0,,Next bit");
    }

    @Test
    void should_clamp_a_caption_that_runs_past_the_clip_end() throws IOException {
        String subtitles = write(WORDS, 0.0, 5.7, Map.of("kirinuki.pipeline.render.words-per-caption", 4));

        assertThat(subtitles).contains("Dialogue: 0,0:00:00.00,0:00:05.70,Default,,0,0,0,,Hello world. Next bit");
    }

    @Test
    void should_drop_words_that_fall_outside_the_clip_window() throws IOException {
        String subtitles = write(WORDS, 4.9, 6.1, Map.of("kirinuki.pipeline.render.words-per-caption", 4));

        assertThat(subtitles).contains(",Next bit").doesNotContain("Hello");
    }

    @Test
    void should_keep_a_word_that_only_partly_overlaps_the_clip_window() throws IOException {
        String subtitles = write(WORDS, 0.7, 6.1, Map.of("kirinuki.pipeline.render.words-per-caption", 1));

        assertThat(subtitles).contains(",world.");
    }

    @Test
    void should_uppercase_captions_when_configured() throws IOException {
        String subtitles = write(WORDS, 0.0, 6.0, Map.of(
                "kirinuki.pipeline.render.words-per-caption", 2,
                "kirinuki.pipeline.render.subtitle-uppercase", true));

        assertThat(subtitles).contains(",HELLO WORLD.");
    }

    @Test
    void should_flatten_a_newline_inside_a_caption_to_a_space() throws IOException {
        String subtitles = write(List.of(new Word(" two\nlines", 0.0, 1.0)), 0.0, 1.0, Map.of());

        assertThat(subtitles).contains(",two lines");
    }

    @Test
    void should_carry_the_render_size_and_subtitle_style_into_the_header() throws IOException {
        String subtitles = write(WORDS, 0.0, 6.0, Map.of(
                "kirinuki.pipeline.render.width", 1080,
                "kirinuki.pipeline.render.height", 1920,
                "kirinuki.pipeline.render.subtitle-font", "Impact",
                "kirinuki.pipeline.render.subtitle-size", 64,
                "kirinuki.pipeline.render.subtitle-margin-bottom", 150));

        assertThat(subtitles)
                .contains("PlayResX: 1080")
                .contains("PlayResY: 1920")
                .contains("Style: Default,Impact,64,")
                .contains(",2,60,60,150,1");
    }

    @Test
    void should_format_timestamps_with_hours_when_the_clip_is_long() throws IOException {
        String subtitles = write(List.of(new Word(" late", 3661.5, 3662.0)), 0.0, 4000.0, Map.of());

        assertThat(subtitles).contains("Dialogue: 0,1:01:01.50,1:01:02.00,");
    }

    @Test
    void should_fail_when_the_subtitle_file_cannot_be_written() {
        SubtitleWriter subtitleWriter = new SubtitleWriter(properties(Map.of()));

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> subtitleWriter.write(WORDS, 0.0, 6.0, directory.resolve("absent/subtitles.ass")))
                .withMessageContaining("Could not write subtitles");
    }

    private String write(List<Word> words, double clipStart, double clipEnd, Map<String, Object> configuration)
            throws IOException {
        Path target = directory.resolve("subtitles.ass");
        new SubtitleWriter(properties(configuration)).write(words, clipStart, clipEnd, target);
        return Files.readString(target);
    }

    private KirinukiPipelineProperties properties(Map<String, Object> configuration) {
        return new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
