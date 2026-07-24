package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.core.annotation.Order;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.scoring.CandidateScorer;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
@Order(5)
class ScoreCandidatesStage implements PipelineStage {

    private final StorageService storageService;
    private final CandidateScorer candidateScorer;

    ScoreCandidatesStage(StorageService storageService, CandidateScorer candidateScorer) {
        this.storageService = storageService;
        this.candidateScorer = candidateScorer;
    }

    @Override
    public String artifact() {
        return Artifacts.SCORED;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.AI_ANALYSIS;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        candidateScorer.score(storageService.resolve(videoId, Artifacts.CANDIDATES),
                storageService.temporaryFor(videoId, Artifacts.SCORED), video.getTitle());
    }
}
