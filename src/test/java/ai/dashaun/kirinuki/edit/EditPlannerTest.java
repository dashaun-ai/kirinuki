package ai.dashaun.kirinuki.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EditPlannerTest {

    private final ObjectMapper objectMapper = new JsonMapper();
    private final TranscriptReader transcriptReader = new TranscriptReader(objectMapper);
    private final ThemeCatalog themeCatalog = mock(ThemeCatalog.class);
    private final EditPlanClient editPlanClient = mock(EditPlanClient.class);
    private final EditPlanValidator validator = new EditPlanValidator(
            new ai.dashaun.kirinuki.config.KirinukiPipelineProperties(
                    null, null, null, null, null, null, null, null, null,
                    new ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Edit(true, true, 3, "spring", "llama4:latest",
                            0.4, 2048, 0.8, 6, 12, 0.6, null, null, null)));

    private final EditPlanner planner = new EditPlanner(objectMapper, transcriptReader, themeCatalog, editPlanClient,
            validator);

    @Test
    void should_return_source_only_fallback_when_client_fails(@TempDir Path directory) throws IOException {
        Path scored = writeScored(directory, candidate(1, 0, 5, "Spring Boot run"));
        Path transcript = writeTranscript(directory, List.of(new Word("Spring", 0, 1), new Word("Boot", 1, 2),
                new Word("run", 2, 3), new Word("now", 3, 4), new Word("please", 4, 5)));
        when(themeCatalog.matches(anyString())).thenReturn(List.of());
        when(themeCatalog.vocabulary()).thenReturn(List.of());
        when(themeCatalog.accent()).thenReturn("#6DB33F");
        when(editPlanClient.plan(anyString(), anyString(), anyDouble(), anyList(), anyList()))
                .thenThrow(new RuntimeException("model unavailable"));

        List<EditPlan> plans = planner.planForVideo(scored, transcript, "Test video");

        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().shots()).allMatch(s -> s.block() == ShotBlock.SOURCE || s.block() == ShotBlock.OUTRO);
    }

    @Test
    void should_snap_emphasis_to_real_words(@TempDir Path directory) throws IOException {
        Path scored = writeScored(directory, candidate(1, 0, 5, "Spring Boot run"));
        Path transcript = writeTranscript(directory, List.of(new Word("Spring", 0, 1), new Word("Boot", 1, 2),
                new Word("run", 2, 3)));
        when(themeCatalog.matches(anyString())).thenReturn(List.of());
        when(themeCatalog.vocabulary()).thenReturn(List.of());
        when(themeCatalog.accent()).thenReturn("#6DB33F");
        when(editPlanClient.plan(anyString(), anyString(), anyDouble(), anyList(), anyList()))
                .thenReturn(new EditPlan(1, "spring",
                        List.of(new Shot(0, 5, ShotBlock.SOURCE, null, null, null, null, null, null, null)),
                        List.of(new Emphasis("run", 2.1, EmphasisKind.PUNCH))));

        List<EditPlan> plans = planner.planForVideo(scored, transcript, "Test video");

        assertThat(plans.getFirst().emphasis().getFirst().at()).isEqualTo(2);
    }

    private Path writeScored(Path directory, ScoredCandidate... scored) throws IOException {
        return Files.writeString(directory.resolve(Artifacts.SCORED), objectMapper.writeValueAsString(List.of(scored)));
    }

    private Path writeTranscript(Path directory, List<Word> words) throws IOException {
        StringBuilder json = new StringBuilder("{\"segments\":[{\"words\":[");
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"word\":\"").append(word.text()).append("\",\"start\":").append(word.start())
                    .append(",\"end\":").append(word.end()).append("}");
        }
        json.append("]}]}");
        return Files.writeString(directory.resolve(Artifacts.TRANSCRIPT), json.toString());
    }

    private ScoredCandidate candidate(int id, double start, double end, String text) {
        return new ScoredCandidate(new Candidate(id, start, end, 0, 0, text),
                new CandidateScore(7, 7, 5, 6, List.of("LinkedIn"), "solid"), 70);
    }
}
