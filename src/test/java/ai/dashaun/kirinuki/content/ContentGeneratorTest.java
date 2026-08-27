package ai.dashaun.kirinuki.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ContentGeneratorTest {

    private static final String VIDEO_TITLE = "Spring Boot 4 in anger";

    private final ObjectMapper objectMapper = new JsonMapper();
    private final ContentGenerationClient contentClient = mock(ContentGenerationClient.class);
    private final ContentGenerator contentGenerator = new ContentGenerator(objectMapper, contentClient);

    @TempDir
    Path directory;

    private Path scoredFile;
    private Path target;

    @BeforeEach
    void setUp() {
        scoredFile = directory.resolve(Artifacts.SCORED);
        target = directory.resolve(Artifacts.CONTENT);
    }

    @Test
    void should_keep_a_clip_whose_content_generation_failed() throws IOException {
        writeScored(scored("first clip", List.of("TikTok")), scored("second clip", List.of("TikTok")));
        when(contentClient.generate(anyString(), eq("first clip"), anyList()))
                .thenThrow(new KirinukiException("model refused"));
        when(contentClient.generate(anyString(), eq("second clip"), anyList())).thenReturn(generated());

        contentGenerator.generate(scoredFile, target, VIDEO_TITLE);

        assertThat(readContent()).extracting(ClipContent::clipIndex).containsExactly(1, 2);
    }

    @Test
    void should_offer_the_suggested_platforms_on_a_clip_whose_content_generation_failed() throws IOException {
        writeScored(scored("first clip", List.of("TikTok", "LinkedIn")), scored("second clip", List.of("X")));
        when(contentClient.generate(anyString(), eq("first clip"), anyList()))
                .thenThrow(new KirinukiException("model refused"));
        when(contentClient.generate(anyString(), eq("second clip"), anyList())).thenReturn(generated());

        contentGenerator.generate(scoredFile, target, VIDEO_TITLE);

        assertThat(readContent().getFirst().platforms())
                .extracting(PlatformVariant::platform)
                .containsExactly("TikTok", "LinkedIn");
    }

    @Test
    void should_fail_when_every_clip_failed_content_generation() throws IOException {
        writeScored(scored("first clip", List.of("TikTok")), scored("second clip", List.of("TikTok")));
        when(contentClient.generate(anyString(), anyString(), anyList()))
                .thenThrow(new KirinukiException("model refused"));

        assertThatExceptionOfType(KirinukiException.class)
                .isThrownBy(() -> contentGenerator.generate(scoredFile, target, VIDEO_TITLE))
                .withMessageContaining("Every clip failed content generation");
    }

    @Test
    void should_write_the_generated_content_when_every_clip_succeeds() throws IOException {
        writeScored(scored("first clip", List.of("TikTok")));
        when(contentClient.generate(anyString(), anyString(), anyList())).thenReturn(generated());

        contentGenerator.generate(scoredFile, target, VIDEO_TITLE);

        assertThat(readContent().getFirst().summary()).isEqualTo("what this clip teaches");
    }

    private GeneratedContent generated() {
        return new GeneratedContent("what this clip teaches", List.of("spring boot"), List.of("java"),
                List.of(new PlatformVariant("TikTok", "title", "caption", List.of("java"), "follow")));
    }

    private ScoredCandidate scored(String text, List<String> suggestedPlatforms) {
        return new ScoredCandidate(new Candidate(1, 10.0, 40.0, 0, 9, text),
                new CandidateScore(9, 8, 7, 9, suggestedPlatforms, "strong hook"), 82);
    }

    private void writeScored(ScoredCandidate... scored) throws IOException {
        Files.writeString(scoredFile, objectMapper.writeValueAsString(List.of(scored)));
    }

    private List<ClipContent> readContent() throws IOException {
        return objectMapper.readValue(Files.readString(target), new TypeReference<List<ClipContent>>() {
        });
    }
}
