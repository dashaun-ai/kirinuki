package ai.dashaun.kirinuki.pipeline.stage;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.CandidateGenerator;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;

@Component
@Order(4)
class CandidateGenerationStage implements PipelineStage {

    private final StorageService storageService;
    private final CandidateGenerator candidateGenerator;

    CandidateGenerationStage(StorageService storageService, CandidateGenerator candidateGenerator) {
        this.storageService = storageService;
        this.candidateGenerator = candidateGenerator;
    }

    @Override
    public String artifact() {
        return Artifacts.CANDIDATES;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.CANDIDATE_GENERATION;
    }

    @Override
    public void run(Video video) {
        String videoId = video.getId().toString();
        candidateGenerator.generate(storageService.resolve(videoId, Artifacts.TRANSCRIPT),
                storageService.temporaryFor(videoId, Artifacts.CANDIDATES));
    }
}
