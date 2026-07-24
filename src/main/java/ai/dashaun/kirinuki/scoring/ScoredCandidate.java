package ai.dashaun.kirinuki.scoring;

import ai.dashaun.kirinuki.candidate.Candidate;

public record ScoredCandidate(Candidate candidate, CandidateScore score, int overallScore) {
}
