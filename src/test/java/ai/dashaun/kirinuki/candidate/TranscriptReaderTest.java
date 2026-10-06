package ai.dashaun.kirinuki.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.common.KirinukiException;
import tools.jackson.databind.json.JsonMapper;

class TranscriptReaderTest {

    private final TranscriptReader transcriptReader = new TranscriptReader(new JsonMapper());

    @TempDir
    Path directory;

    @Test
    void should_read_words_across_every_segment_in_order() throws IOException {
        Path transcript = write("""
                {
                  "segments": [
                    {"words": [{"word": " Spring", "start": 0.0, "end": 0.4},
                               {"word": " Boot", "start": 0.4, "end": 0.9}]},
                    {"words": [{"word": " four.", "start": 1.2, "end": 1.8}]}
                  ]
                }
                """);

        List<Word> words = transcriptReader.readWords(transcript);

        assertThat(words).containsExactly(
                new Word(" Spring", 0.0, 0.4),
                new Word(" Boot", 0.4, 0.9),
                new Word(" four.", 1.2, 1.8));
    }

    @Test
    void should_fail_when_the_transcript_has_segments_but_no_word_timestamps() throws IOException {
        Path transcript = write("""
                {"segments": [{"text": "Spring Boot four."}]}
                """);

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> transcriptReader.readWords(transcript))
                .withMessageContaining("no word-level timestamps");
    }

    @Test
    void should_read_whisper_cpp_word_level_segments_converting_milliseconds_to_seconds() throws IOException {
        Path transcript = write("""
                {
                  "transcription": [
                    {"text": "", "offsets": {"from": 0, "to": 0}},
                    {"text": " Spring", "offsets": {"from": 0, "to": 640}},
                    {"text": " Boot", "offsets": {"from": 640, "to": 1280}},
                    {"text": " four.", "offsets": {"from": 1280, "to": 1880}}
                  ]
                }
                """);

        List<Word> words = transcriptReader.readWords(transcript);

        assertThat(words).containsExactly(
                new Word(" Spring", 0.0, 0.64),
                new Word(" Boot", 0.64, 1.28),
                new Word(" four.", 1.28, 1.88));
    }

    @Test
    void should_fail_when_the_whisper_cpp_transcript_has_no_word_level_offsets() throws IOException {
        Path transcript = write("""
                {"transcription": [{"text": "Spring Boot four."}]}
                """);

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> transcriptReader.readWords(transcript))
                .withMessageContaining("no word-level timestamps");
    }

    @Test
    void should_fail_when_the_transcript_is_missing() {
        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> transcriptReader.readWords(directory.resolve("absent.json")))
                .withMessageContaining("Could not read transcript");
    }

    private Path write(String json) throws IOException {
        return Files.writeString(directory.resolve("transcript.json"), json);
    }
}
