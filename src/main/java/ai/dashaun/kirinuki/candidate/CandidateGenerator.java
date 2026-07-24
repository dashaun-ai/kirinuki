package ai.dashaun.kirinuki.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CandidateGenerator {
    private static final Logger log = LoggerFactory.getLogger(CandidateGenerator.class);

    private final KirinukiPipelineProperties properties;
    private final ObjectMapper objectMapper;

    public CandidateGenerator(KirinukiPipelineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void generate(Path transcript, Path target) {
        List<Word> words = readWords(transcript);
        List<Sentence> sentences = splitIntoSentences(words);
        List<Candidate> candidates = window(sentences);
        write(candidates, target);
    }

    private List<Word> readWords(Path transcript) {
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

    private List<Sentence> splitIntoSentences(List<Word> words) {
        List<Sentence> sentences = new ArrayList<>();
        int firstIndex = 0;
        for (int index = 0; index < words.size(); index++) {
            if (!endsSentence(words.get(index).text()) && index != words.size() - 1) {
                continue;
            }
            sentences.add(sentenceOf(words, firstIndex, index));
            firstIndex = index + 1;
        }
        return sentences;
    }

    private boolean endsSentence(String word) {
        String trimmed = word.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!");
    }

    private Sentence sentenceOf(List<Word> words, int firstIndex, int lastIndex) {
        StringBuilder text = new StringBuilder();
        for (int index = firstIndex; index <= lastIndex; index++) {
            text.append(words.get(index).text());
        }
        return new Sentence(firstIndex, lastIndex, words.get(firstIndex).start(), words.get(lastIndex).end(),
                text.toString().strip());
    }

    private List<Candidate> window(List<Sentence> sentences) {
        List<Group> groups = groups(sentences);
        List<Group> kept = thinEvenly(groups, properties.candidates().maxCandidates());

        List<Candidate> candidates = new ArrayList<>();
        for (Group group : kept) {
            candidates.add(candidateOf(candidates.size(), sentences, group.start(), group.shortEnd()));
            if (group.hasLongWindow()) {
                candidates.add(candidateOf(candidates.size(), sentences, group.start(), group.longEnd()));
            }
        }
        return candidates;
    }

    private List<Group> groups(List<Sentence> sentences) {
        double minimum = properties.candidates().minDuration().toSeconds();
        double maximum = properties.candidates().maxDuration().toSeconds();
        List<Group> groups = new ArrayList<>();

        int start = 0;
        while (start < sentences.size()) {
            int shortEnd = start;
            while (shortEnd < sentences.size()
                    && sentences.get(shortEnd).end() - sentences.get(start).start() < minimum) {
                shortEnd++;
            }
            if (shortEnd >= sentences.size()) {
                break;
            }
            int longEnd = shortEnd;
            while (longEnd + 1 < sentences.size()
                    && sentences.get(longEnd + 1).end() - sentences.get(start).start() <= maximum) {
                longEnd++;
            }
            groups.add(new Group(start, shortEnd, longEnd));
            start = longEnd + 1;
        }
        return groups;
    }

    private List<Group> thinEvenly(List<Group> groups, int limit) {
        int groupLimit = Math.max(1, limit / 2);
        if (groups.size() <= groupLimit) {
            return groups;
        }
        log.info("Thinning {} candidate groups to {} spread across the transcript", groups.size(), groupLimit);
        List<Group> kept = new ArrayList<>(groupLimit);
        double step = (double) groups.size() / groupLimit;
        for (int index = 0; index < groupLimit; index++) {
            kept.add(groups.get((int) (index * step)));
        }
        return kept;
    }

    private Candidate candidateOf(int id, List<Sentence> sentences, int start, int end) {
        Sentence first = sentences.get(start);
        Sentence last = sentences.get(end);
        return new Candidate(id, first.start(), last.end(), first.firstWordIndex(), last.lastWordIndex(),
                textOf(sentences, start, end));
    }

    private String textOf(List<Sentence> sentences, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int index = start; index <= end; index++) {
            text.append(text.isEmpty() ? "" : " ").append(sentences.get(index).text());
        }
        return text.toString();
    }

    private JsonNode read(Path transcript) {
        try {
            return objectMapper.readTree(Files.readString(transcript));
        } catch (IOException exception) {
            throw new KirinukiException("Could not read transcript " + transcript, exception);
        }
    }

    private void write(List<Candidate> candidates, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(candidates));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write candidates to " + target, exception);
        }
    }

    private record Group(int start, int shortEnd, int longEnd) {
        boolean hasLongWindow() {
            return longEnd != shortEnd;
        }
    }

    private record Sentence(int firstWordIndex, int lastWordIndex, double start, double end, String text) {
    }
}
