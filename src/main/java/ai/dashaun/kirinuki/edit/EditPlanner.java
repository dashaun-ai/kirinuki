package ai.dashaun.kirinuki.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.vision.Scene;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class EditPlanner {

    private static final Logger log = LoggerFactory.getLogger(EditPlanner.class);

    private final ObjectMapper objectMapper;
    private final TranscriptReader transcriptReader;
    private final ThemeCatalog themeCatalog;
    private final EditPlanClient editPlanClient;
    private final EditPlanValidator validator;

    public EditPlanner(ObjectMapper objectMapper, TranscriptReader transcriptReader, ThemeCatalog themeCatalog,
            EditPlanClient editPlanClient, EditPlanValidator validator) {
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.themeCatalog = themeCatalog;
        this.editPlanClient = editPlanClient;
        this.validator = validator;
    }

    public List<EditPlan> planForVideo(Path scoredFile, Path transcriptFile, Path sceneFile, String videoTitle) {
        List<ScoredCandidate> scored = readScored(scoredFile);
        List<Word> words = transcriptReader.readWords(transcriptFile);
        Scene scene = readScene(sceneFile);
        List<EditPlan> plans = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            int clipIndex = index + 1;
            ScoredCandidate candidate = scored.get(index);
            try {
                plans.add(planForClip(clipIndex, videoTitle, candidate, scene, words));
            } catch (RuntimeException exception) {
                log.warn("Edit planning failed for clip {} — falling back to source-only", clipIndex, exception);
                plans.add(sourceOnly(clipIndex, candidate.candidate().durationSeconds()));
            }
        }
        return plans;
    }

    public EditPlan planForClip(int clipIndex, String videoTitle, ScoredCandidate candidate, Scene scene,
            List<Word> words) {
        String transcript = candidate.candidate().text();
        double duration = candidate.candidate().durationSeconds();
        List<ThemeTopic> topics = themeCatalog.matches(transcript);
        EditPlan raw = editPlanClient.plan(videoTitle, transcript, duration, scene, topics, themeCatalog.vocabulary());
        EditPlan withIndex = new EditPlan(clipIndex, themeCatalog.accent(), raw.shots(), raw.emphasis());
        return validator.validateAndRepair(withIndex, duration, scene, words);
    }

    public EditPlan sourceOnly(int clipIndex, double duration) {
        List<Shot> shots = new ArrayList<>();
        double outroStart = Math.max(0, duration - 2.5);
        if (outroStart >= 0.8) {
            shots.add(new Shot(0, outroStart, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null,
                    null, null, null));
            shots.add(new Shot(outroStart, duration, ShotBlock.OUTRO, "Follow for more", null, null, null, null, null,
                    null, null, null, null, null, null));
        } else {
            shots.add(new Shot(0, duration, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null,
                    null, null, null));
        }
        return new EditPlan(clipIndex, themeCatalog.accent(), shots, List.of());
    }

    private List<ScoredCandidate> readScored(Path scoredFile) {
        try {
            return objectMapper.readValue(Files.readString(scoredFile), new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scored candidates " + scoredFile, exception);
        }
    }

    private Scene readScene(Path sceneFile) {
        try {
            if (!Files.exists(sceneFile)) {
                return null;
            }
            return objectMapper.readValue(Files.readString(sceneFile), Scene.class);
        } catch (IOException exception) {
            log.warn("Could not read scene analysis from {} — continuing without it", sceneFile, exception);
            return null;
        }
    }
}
