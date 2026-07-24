package ai.dashaun.kirinuki.scoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Scoring.Weights;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class CandidateScorer {

    private static final double MAX_OVERLAP = 0.5;
    private static final Logger log = LoggerFactory.getLogger(CandidateScorer.class);

    private final KirinukiPipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final CandidateScoreClient scoreClient;

    public CandidateScorer(KirinukiPipelineProperties properties, ObjectMapper objectMapper,
            CandidateScoreClient scoreClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.scoreClient = scoreClient;
    }

    public void score(Path candidatesFile, Path target, String videoTitle) {
        List<Candidate> candidates = readCandidates(candidatesFile);

        List<ScoredCandidate> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            scoreOne(candidate, videoTitle).ifPresent(scored::add);
        }
        if (scored.isEmpty() && !candidates.isEmpty()) {
            throw new KirinukiException("Every candidate failed scoring; the model is likely unavailable");
        }

        List<ScoredCandidate> ranked = rank(scored);
        log.info("Scored {} of {} candidates, kept top {} distinct moments", scored.size(), candidates.size(),
                ranked.size());
        write(ranked, target);
    }

    private List<ScoredCandidate> rank(List<ScoredCandidate> scored) {
        List<ScoredCandidate> byScore = scored.stream()
                .sorted(Comparator.comparingInt(ScoredCandidate::overallScore).reversed())
                .toList();

        List<ScoredCandidate> kept = new ArrayList<>();
        for (ScoredCandidate candidate : byScore) {
            if (kept.size() == properties.scoring().topClips()) {
                break;
            }
            if (kept.stream().noneMatch(existing -> overlaps(existing, candidate))) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private boolean overlaps(ScoredCandidate first, ScoredCandidate second) {
        double shared = Math.min(first.candidate().end(), second.candidate().end())
                - Math.max(first.candidate().start(), second.candidate().start());
        if (shared <= 0) {
            return false;
        }
        double shorter = Math.min(first.candidate().durationSeconds(), second.candidate().durationSeconds());
        return shared / shorter > MAX_OVERLAP;
    }

    private Optional<ScoredCandidate> scoreOne(Candidate candidate, String videoTitle) {
        try {
            CandidateScore score = scoreClient.score(candidate, videoTitle).clamped();
            return Optional.of(new ScoredCandidate(candidate, score, overallScore(score)));
        } catch (RuntimeException exception) {
            log.warn("Skipping candidate {} — scoring failed: {}", candidate.id(), exception.getMessage());
            return Optional.empty();
        }
    }

    private int overallScore(CandidateScore score) {
        Weights weights = properties.scoring().weights();
        int weighted = weights.hook() * score.hook()
                + weights.educationalValue() * score.educationalValue()
                + weights.emotion() * score.emotion()
                + weights.visualInterest() * score.visualInterest()
                + weights.virality() * score.virality();
        return Math.round(100f * weighted / (10 * weights.total()));
    }

    private List<Candidate> readCandidates(Path candidatesFile) {
        try {
            return objectMapper.readValue(Files.readString(candidatesFile), new TypeReference<List<Candidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read candidates " + candidatesFile, exception);
        }
    }

    private void write(List<ScoredCandidate> scored, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(scored));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write scores to " + target, exception);
        }
    }
}
