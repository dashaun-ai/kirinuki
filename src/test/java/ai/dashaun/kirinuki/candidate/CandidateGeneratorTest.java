package ai.dashaun.kirinuki.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.AudioFeatures;
import ai.dashaun.kirinuki.media.Silence;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CandidateGeneratorTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @TempDir
    Path directory;

    @Test
    void should_emit_a_short_and_a_long_window_for_each_group() throws IOException {
        List<Candidate> candidates = generate(sentences(10), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s"));

        assertThat(candidates)
                .extracting(Candidate::start, Candidate::end)
                .containsExactly(
                        tuple(0.0, 2.0),
                        tuple(0.0, 5.0),
                        tuple(5.0, 7.0),
                        tuple(5.0, 10.0));
    }

    @Test
    void should_number_candidates_sequentially_from_zero() throws IOException {
        List<Candidate> candidates = generate(sentences(10), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s"));

        assertThat(candidates).extracting(Candidate::id).containsExactly(0, 1, 2, 3);
    }

    @Test
    void should_carry_the_word_range_of_the_window_onto_the_candidate() throws IOException {
        List<Candidate> candidates = generate(sentences(10), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s"));

        assertThat(candidates.getFirst().firstWordIndex()).isZero();
        assertThat(candidates.getFirst().lastWordIndex()).isEqualTo(1);
    }

    @Test
    void should_emit_only_the_short_window_when_the_group_cannot_be_extended() throws IOException {
        List<Candidate> candidates = generate(sentences(4), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "2s"));

        assertThat(candidates)
                .extracting(Candidate::start, Candidate::end)
                .containsExactly(tuple(0.0, 2.0), tuple(2.0, 4.0));
    }

    @Test
    void should_keep_the_whole_transcript_together_when_nothing_marks_a_boundary() throws IOException {
        List<Candidate> candidates = generate(unpunctuated(4), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "1s",
                "kirinuki.pipeline.candidates.max-duration", "2s"));

        assertThat(candidates).extracting(Candidate::text).containsExactly("a b c d");
    }

    @Test
    void should_split_on_a_silence_when_the_words_never_end_a_sentence() throws IOException {
        List<Candidate> candidates = generate(unpunctuated(4), List.of(new Silence(2.0, 2.4)), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "1s",
                "kirinuki.pipeline.candidates.max-duration", "2s"));

        assertThat(candidates).extracting(Candidate::text).containsExactly("a b", "c d");
    }

    @Test
    void should_split_on_a_scene_cut_when_the_words_never_end_a_sentence() throws IOException {
        List<Candidate> candidates = generate(unpunctuated(4), List.of(), List.of(2.2), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "1s",
                "kirinuki.pipeline.candidates.max-duration", "2s"));

        assertThat(candidates).extracting(Candidate::text).containsExactly("a b", "c d");
    }

    @Test
    void should_join_segment_texts_with_a_single_space_inside_a_window() throws IOException {
        List<Candidate> candidates = generate(sentences(4), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "2s"));

        assertThat(candidates.getFirst().text()).isEqualTo("w0. w1.");
    }

    @Test
    void should_thin_the_groups_evenly_when_they_exceed_the_candidate_limit() throws IOException {
        List<Candidate> candidates = generate(sentences(20), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s",
                "kirinuki.pipeline.candidates.max-candidates", 4));

        assertThat(candidates).extracting(Candidate::start).containsExactly(0.0, 0.0, 10.0, 10.0);
    }

    @Test
    void should_keep_every_group_when_they_fit_inside_the_candidate_limit() throws IOException {
        List<Candidate> candidates = generate(sentences(20), List.of(), List.of(), Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s",
                "kirinuki.pipeline.candidates.max-candidates", 40));

        assertThat(candidates).extracting(Candidate::start).containsExactly(0.0, 0.0, 5.0, 5.0, 10.0, 10.0, 15.0, 15.0);
    }

    @Test
    void should_ignore_missing_scene_and_audio_feature_files() throws IOException {
        Path transcript = writeTranscript(sentences(10));
        CandidateGenerator generator = generator(Map.of(
                "kirinuki.pipeline.candidates.min-duration", "2s",
                "kirinuki.pipeline.candidates.max-duration", "5s"));
        Path target = directory.resolve("candidates.json");

        generator.generate(transcript, directory.resolve("absent-scenes.json"),
                directory.resolve("absent-audio.json"), target);

        assertThat(readCandidates(target)).hasSize(4);
    }

    @Test
    void should_fail_when_the_scenes_file_cannot_be_read() throws IOException {
        Path transcript = writeTranscript(sentences(10));
        Path scenes = Files.createDirectory(directory.resolve("scenes.json"));
        CandidateGenerator generator = generator(Map.of());

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> generator.generate(transcript, scenes, directory.resolve("absent-audio.json"),
                        directory.resolve("candidates.json")))
                .withMessageContaining("Could not read scenes");
    }

    private List<Candidate> generate(List<Word> words, List<Silence> silences, List<Double> sceneCuts,
            Map<String, Object> configuration) throws IOException {
        Path target = directory.resolve("candidates.json");
        generator(configuration).generate(writeTranscript(words), writeScenes(sceneCuts), writeAudio(silences), target);
        return readCandidates(target);
    }

    private CandidateGenerator generator(Map<String, Object> configuration) {
        return new CandidateGenerator(properties(configuration), objectMapper, new TranscriptReader(objectMapper));
    }

    private List<Word> sentences(int count) {
        List<Word> words = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            words.add(new Word(" w%d.".formatted(index), index, index + 1));
        }
        return words;
    }

    private List<Word> unpunctuated(int count) {
        List<Word> words = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            words.add(new Word(" %s".formatted((char) ('a' + index)), index, index + 1));
        }
        return words;
    }

    private Path writeTranscript(List<Word> words) throws IOException {
        List<Map<String, Object>> serialised = words.stream()
                .map(word -> Map.<String, Object>of("word", word.text(), "start", word.start(), "end", word.end()))
                .toList();
        String json = objectMapper.writeValueAsString(Map.of("segments", List.of(Map.of("words", serialised))));
        return Files.writeString(directory.resolve("transcript.json"), json);
    }

    private Path writeScenes(List<Double> sceneCuts) throws IOException {
        return Files.writeString(directory.resolve("scenes.json"), objectMapper.writeValueAsString(sceneCuts));
    }

    private Path writeAudio(List<Silence> silences) throws IOException {
        return Files.writeString(directory.resolve("audio-features.json"),
                objectMapper.writeValueAsString(new AudioFeatures(silences)));
    }

    private List<Candidate> readCandidates(Path target) throws IOException {
        return objectMapper.readValue(Files.readString(target), new TypeReference<List<Candidate>>() {
        });
    }

    private KirinukiPipelineProperties properties(Map<String, Object> configuration) {
        return new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
