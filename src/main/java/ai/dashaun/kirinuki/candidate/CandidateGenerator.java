package ai.dashaun.kirinuki.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.AudioFeatures;
import ai.dashaun.kirinuki.media.Silence;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class CandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(CandidateGenerator.class);

    private final KirinukiPipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final TranscriptReader transcriptReader;

    public CandidateGenerator(KirinukiPipelineProperties properties, ObjectMapper objectMapper,
            TranscriptReader transcriptReader) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
    }

    public void generate(Path transcript, Path scenesFile, Path audioFeaturesFile, Path target) {
        List<Word> words = transcriptReader.readWords(transcript);
        List<Silence> silences = readSilences(audioFeaturesFile);
        List<Double> sceneCuts = readScenes(scenesFile);
        List<Segment> segments = splitIntoSegments(words, silences, sceneCuts);
        List<Candidate> candidates = window(segments);
        write(candidates, target);
    }

    private List<Segment> splitIntoSegments(List<Word> words, List<Silence> silences, List<Double> sceneCuts) {
        TreeSet<Integer> boundaries = new TreeSet<>();
        for (int index = 0; index < words.size(); index++) {
            if (endsSentence(words.get(index).text())) {
                boundaries.add(index);
            }
        }
        for (Silence silence : silences) {
            int index = lastWordEndingBefore(words, (silence.start() + silence.end()) / 2);
            if (index >= 0) {
                boundaries.add(index);
            }
        }
        for (double cut : sceneCuts) {
            int index = lastWordEndingBefore(words, cut);
            if (index >= 0) {
                boundaries.add(index);
            }
        }
        boundaries.add(words.size() - 1);

        List<Segment> segments = new ArrayList<>();
        int firstIndex = 0;
        for (int lastIndex : boundaries) {
            if (lastIndex < firstIndex) {
                continue;
            }
            segments.add(segmentOf(words, firstIndex, lastIndex));
            firstIndex = lastIndex + 1;
        }
        return segments;
    }

    private int lastWordEndingBefore(List<Word> words, double time) {
        int found = -1;
        for (int index = 0; index < words.size(); index++) {
            if (words.get(index).end() <= time) {
                found = index;
            } else {
                break;
            }
        }
        return found;
    }

    private boolean endsSentence(String word) {
        String trimmed = word.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!");
    }

    private Segment segmentOf(List<Word> words, int firstIndex, int lastIndex) {
        StringBuilder text = new StringBuilder();
        for (int index = firstIndex; index <= lastIndex; index++) {
            text.append(words.get(index).text());
        }
        return new Segment(firstIndex, lastIndex, words.get(firstIndex).start(), words.get(lastIndex).end(),
                text.toString().strip());
    }

    private List<Candidate> window(List<Segment> segments) {
        List<Group> groups = groups(segments);
        List<Group> kept = thinEvenly(groups, properties.candidates().maxCandidates());

        List<Candidate> candidates = new ArrayList<>();
        for (Group group : kept) {
            candidates.add(candidateOf(candidates.size(), segments, group.start(), group.shortEnd()));
            if (group.hasLongWindow()) {
                candidates.add(candidateOf(candidates.size(), segments, group.start(), group.longEnd()));
            }
        }
        return candidates;
    }

    private List<Group> groups(List<Segment> segments) {
        double minimum = properties.candidates().minDuration().toSeconds();
        double maximum = properties.candidates().maxDuration().toSeconds();
        List<Group> groups = new ArrayList<>();

        int start = 0;
        while (start < segments.size()) {
            int shortEnd = start;
            while (shortEnd < segments.size()
                    && segments.get(shortEnd).end() - segments.get(start).start() < minimum) {
                shortEnd++;
            }
            if (shortEnd >= segments.size()) {
                break;
            }
            int longEnd = shortEnd;
            while (longEnd + 1 < segments.size()
                    && segments.get(longEnd + 1).end() - segments.get(start).start() <= maximum) {
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

    private Candidate candidateOf(int id, List<Segment> segments, int start, int end) {
        Segment first = segments.get(start);
        Segment last = segments.get(end);
        return new Candidate(id, first.start(), last.end(), first.firstWordIndex(), last.lastWordIndex(),
                textOf(segments, start, end));
    }

    private String textOf(List<Segment> segments, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int index = start; index <= end; index++) {
            text.append(text.isEmpty() ? "" : " ").append(segments.get(index).text());
        }
        return text.toString();
    }

    private void write(List<Candidate> candidates, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(candidates));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write candidates to " + target, exception);
        }
    }

    private List<Silence> readSilences(Path audioFeaturesFile) {
        if (!Files.exists(audioFeaturesFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(audioFeaturesFile), AudioFeatures.class).silences();
        } catch (IOException exception) {
            throw new KirinukiException("Could not read audio features " + audioFeaturesFile, exception);
        }
    }

    private List<Double> readScenes(Path scenesFile) {
        if (!Files.exists(scenesFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(scenesFile), new TypeReference<List<Double>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scenes " + scenesFile, exception);
        }
    }

    private record Group(int start, int shortEnd, int longEnd) {
        boolean hasLongWindow() {
            return longEnd != shortEnd;
        }
    }

    private record Segment(int firstWordIndex, int lastWordIndex, double start, double end, String text) {
    }
}
