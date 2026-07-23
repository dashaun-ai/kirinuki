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

    // Whisper segments break mid-phrase, so sentences are rebuilt from terminal punctuation instead.
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

    // Each start yields a short and a long window so the scorer can judge whether a moment plays
    // better tight or extended; the next start resumes after the long one, keeping the count down
    // while still covering the whole video.
    private List<Candidate> window(List<Sentence> sentences) {
        List<int[]> spans = spans(sentences);
        List<int[]> kept = thinEvenly(spans, properties.candidates().maxCandidates());

        List<Candidate> candidates = new ArrayList<>();
        for (int[] span : kept) {
            candidates.add(candidateOf(candidates.size(), sentences, span[0], span[1]));
        }
        return candidates;
    }

    private List<int[]> spans(List<Sentence> sentences) {
        double minimum = properties.candidates().minDuration().toSeconds();
        double maximum = properties.candidates().maxDuration().toSeconds();
        List<int[]> spans = new ArrayList<>();

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
            spans.add(new int[] { start, shortEnd });
            if (longEnd != shortEnd) {
                spans.add(new int[] { start, longEnd });
            }
            start = longEnd + 1;
        }
        return spans;
    }

    // Sampling across the whole transcript rather than truncating: a long video would otherwise
    // yield candidates only from its opening minutes and the rest would never reach scoring.
    private List<int[]> thinEvenly(List<int[]> spans, int limit) {
        if (spans.size() <= limit) {
            return spans;
        }
        log.info("Thinning {} windows to {} spread across the transcript", spans.size(), limit);
        List<int[]> kept = new ArrayList<>(limit);
        double step = (double) spans.size() / limit;
        for (int index = 0; index < limit; index++) {
            kept.add(spans.get((int) (index * step)));
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

    private record Sentence(int firstWordIndex, int lastWordIndex, double start, double end, String text) {
    }
}
