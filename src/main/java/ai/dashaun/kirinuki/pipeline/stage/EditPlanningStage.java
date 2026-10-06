package ai.dashaun.kirinuki.pipeline.stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.edit.DesignedEditService;
import ai.dashaun.kirinuki.edit.EditPlan;
import ai.dashaun.kirinuki.edit.EditPlanner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class EditPlanningStage implements PipelineStage {

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final TranscriptReader transcriptReader;
    private final EditPlanner editPlanner;
    private final DesignedEditService designedEditService;
    private final KirinukiPipelineProperties properties;

    EditPlanningStage(StorageService storageService, ObjectMapper objectMapper, TranscriptReader transcriptReader,
            EditPlanner editPlanner, DesignedEditService designedEditService, KirinukiPipelineProperties properties) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.editPlanner = editPlanner;
        this.designedEditService = designedEditService;
        this.properties = properties;
    }

    @Override
    public String artifact() {
        return Artifacts.EDIT_PLANS;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.EDIT_PLANNING;
    }

    @Override
    @ConcurrencyLimit(1)
    public void run(Video video) {
        String videoId = video.getId().toString();
        Path scoredFile = storageService.resolve(videoId, Artifacts.SCORED);
        Path transcriptFile = storageService.resolve(videoId, Artifacts.TRANSCRIPT);
        Path target = storageService.temporaryFor(videoId, Artifacts.EDIT_PLANS);

        List<EditPlan> plans;
        if (properties.edit().enabled()) {
            plans = editPlanner.planForVideo(scoredFile, transcriptFile, video.getTitle());
        } else {
            plans = sourceOnlyPlans(scoredFile, transcriptFile);
        }

        try {
            Files.writeString(target, objectMapper.writeValueAsString(plans));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write edit plans to " + target, exception);
        }
        designedEditService.queueAutomaticRenders(video, plans);
    }

    private List<EditPlan> sourceOnlyPlans(Path scoredFile, Path transcriptFile) {
        List<ScoredCandidate> scored;
        try {
            scored = objectMapper.readValue(Files.readString(scoredFile), new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scored candidates " + scoredFile, exception);
        }
        double videoDuration = transcriptReader.readWords(transcriptFile).getLast().end();
        List<EditPlan> plans = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            ScoredCandidate candidate = scored.get(index);
            double end = Math.min(candidate.candidate().end(), videoDuration);
            double start = Math.min(candidate.candidate().start(), end);
            plans.add(editPlanner.sourceOnly(index + 1, end - start));
        }
        return plans;
    }
}
