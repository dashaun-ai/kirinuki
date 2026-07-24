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
        for (JsonNode segment : root.path("segments")) {
            for (JsonNode word : segment.path("words")) {
                words.add(new Word(word.path("word").asString(), word.path("start").asDouble(),
                        word.path("end").asDouble()));
            }
        }
        if (words.isEmpty()) {
            throw new KirinukiException("Transcript has no word-level timestamps: " + transcript);
        }
        return words;
    }

    private JsonNode read(Path transcript) {
        try {
            return objectMapper.readTree(Files.readString(transcript));
        } catch (IOException exception) {
            throw new KirinukiException("Could not read transcript " + transcript, exception);
        }
    }
}
