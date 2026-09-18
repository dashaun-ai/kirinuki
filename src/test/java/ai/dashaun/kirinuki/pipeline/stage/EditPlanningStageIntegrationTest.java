package ai.dashaun.kirinuki.pipeline.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ai.dashaun.kirinuki.AbstractIntegrationTest;
import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.edit.EditPlan;
import ai.dashaun.kirinuki.edit.EditPlanClient;
import ai.dashaun.kirinuki.edit.Shot;
import ai.dashaun.kirinuki.edit.ShotBlock;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineOrchestrator;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class EditPlanningStageIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private EditPlanClient editPlanClient;

    @Autowired
    private PipelineOrchestrator pipelineOrchestrator;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_write_edit_plans_and_advance_to_ready_for_review() throws IOException {
        Video video = videoRepository.save(new Video(UUID.randomUUID(), "dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Spring Boot 4 in anger", 840, "your_javaguy",
                PipelineStatus.CONTENT_GENERATION, Instant.now(), null));
        writeContent(video.getId());
        writeScored(video.getId());
        writeTranscript(video.getId());
        when(editPlanClient.plan(anyString(), anyString(), anyDouble(), anyList(), anyList()))
                .thenReturn(new EditPlan(1, "spring",
                        List.of(new Shot(0, 5, ShotBlock.SOURCE, null, null, null, null, null, null, null)), List.of()));

        pipelineOrchestrator.advance(video.getId());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PipelineStatus.READY_FOR_REVIEW);
        assertThat(storageService.exists(video.getId().toString(), Artifacts.EDIT_PLANS)).isTrue();
        List<EditPlan> plans = objectMapper.readValue(
                Files.readString(storageService.resolve(video.getId().toString(), Artifacts.EDIT_PLANS)),
                new TypeReference<List<EditPlan>>() {
                });
        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().clipIndex()).isEqualTo(1);
    }

    @Test
    void should_write_source_only_plan_when_client_fails() throws IOException {
        Video video = videoRepository.save(new Video(UUID.randomUUID(), "dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Spring Boot 4 in anger", 840, "your_javaguy",
                PipelineStatus.CONTENT_GENERATION, Instant.now(), null));
        writeContent(video.getId());
        writeScored(video.getId());
        writeTranscript(video.getId());
        when(editPlanClient.plan(anyString(), anyString(), anyDouble(), anyList(), anyList()))
                .thenThrow(new RuntimeException("model unavailable"));

        pipelineOrchestrator.advance(video.getId());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PipelineStatus.READY_FOR_REVIEW);
        List<EditPlan> plans = objectMapper.readValue(
                Files.readString(storageService.resolve(video.getId().toString(), Artifacts.EDIT_PLANS)),
                new TypeReference<List<EditPlan>>() {
                });
        assertThat(plans.getFirst().shots()).allMatch(s -> s.block() == ShotBlock.SOURCE || s.block() == ShotBlock.OUTRO);
    }

    private void writeContent(UUID videoId) throws IOException {
        Path target = storageService.temporaryFor(videoId.toString(), Artifacts.CONTENT);
        Files.writeString(target, objectMapper.writeValueAsString(List.of(
                new ai.dashaun.kirinuki.content.ClipContent(1, "summary", List.of("keyword"), List.of("tag"),
                        List.of()))));
        storageService.commit(videoId.toString(), Artifacts.CONTENT);
    }

    private void writeScored(UUID videoId) throws IOException {
        ScoredCandidate candidate = new ScoredCandidate(new Candidate(1, 10.0, 40.0, 0, 9, "the moment a viewer would clip"),
                new CandidateScore(9, 8, 7, 9, List.of("TikTok"), "strong hook"), 82);
        Path target = storageService.temporaryFor(videoId.toString(), Artifacts.SCORED);
        Files.writeString(target, objectMapper.writeValueAsString(List.of(candidate)));
        storageService.commit(videoId.toString(), Artifacts.SCORED);
    }

    private void writeTranscript(UUID videoId) throws IOException {
        Path target = storageService.temporaryFor(videoId.toString(), Artifacts.TRANSCRIPT);
        Files.writeString(target, """
                {"segments":[{"words":[
                {"word":"the","start":10.0,"end":10.5},
                {"word":"moment","start":10.5,"end":11.0},
                {"word":"a","start":11.0,"end":11.2},
                {"word":"viewer","start":11.2,"end":11.6},
                {"word":"would","start":11.6,"end":11.9},
                {"word":"clip","start":11.9,"end":12.0}
                ]}]}""");
        storageService.commit(videoId.toString(), Artifacts.TRANSCRIPT);
    }
}
