package ai.dashaun.kirinuki.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TranscriptReader {

    private final ObjectMapper objectMapper;

    public TranscriptReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Word> readWords(Path transcript) {
        JsonNode root = read(transcript);
        List<Word> words = new ArrayList<>();
        JsonNode segments = root.path("segments");
        if (segments.isArray() && !segments.isEmpty()) {
            readCTranslate2Words(segments, words);
        } else if (root.path("transcription").isArray()) {
            readWhisperCppWords(root.path("transcription"), words);
        }
        if (words.isEmpty()) {
            throw new KirinukiException("Transcript has no word-level timestamps: " + transcript);
        }
        return words;
    }

    private void readCTranslate2Words(JsonNode segments, List<Word> words) {
        for (JsonNode segment : segments) {
            for (JsonNode word : segment.path("words")) {
                words.add(new Word(word.path("word").asString(), word.path("start").asDouble(),
                        word.path("end").asDouble()));
            }
        }
    }

    private void readWhisperCppWords(JsonNode transcription, List<Word> words) {
        for (JsonNode segment : transcription) {
            JsonNode offsets = segment.path("offsets");
            String text = segment.path("text").asString();
            if (text.isBlank() || offsets.isMissingNode()) {
                continue;
            }
            words.add(new Word(text, millisToSeconds(offsets.path("from").asLong()),
                    millisToSeconds(offsets.path("to").asLong())));
        }
    }

    private double millisToSeconds(long millis) {
        return millis / 1000.0;
    }

    private JsonNode read(Path transcript) {
        try {
            return objectMapper.readTree(Files.readString(transcript));
        } catch (IOException exception) {
            throw new KirinukiException("Could not read transcript " + transcript, exception);
        }
    }
}
