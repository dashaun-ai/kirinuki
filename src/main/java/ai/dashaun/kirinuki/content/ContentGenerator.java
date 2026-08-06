package ai.dashaun.kirinuki.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ContentGenerator {

    private static final Logger log = LoggerFactory.getLogger(ContentGenerator.class);
    private static final List<String> DEFAULT_PLATFORMS = List.of("TikTok", "Shorts", "LinkedIn", "X");

    private final ObjectMapper objectMapper;
    private final ContentGenerationClient contentClient;

    public ContentGenerator(ObjectMapper objectMapper, ContentGenerationClient contentClient) {
        this.objectMapper = objectMapper;
        this.contentClient = contentClient;
    }

    public void generate(Path scoredFile, Path target, String videoTitle) {
        List<ScoredCandidate> clips = readScored(scoredFile);

        List<ClipContent> content = new ArrayList<>();
        for (int index = 0; index < clips.size(); index++) {
            int clipIndex = index + 1;
            log.info("Generating content for clip {}/{}", clipIndex, clips.size());
            generateOne(clipIndex, clips.get(index), videoTitle).ifPresent(content::add);
        }
        if (content.isEmpty() && !clips.isEmpty()) {
            throw new KirinukiException("Every clip failed content generation; the model is likely unavailable");
        }
        write(content, target);
    }

    private Optional<ClipContent> generateOne(int clipIndex, ScoredCandidate clip, String videoTitle) {
        try {
            GeneratedContent generated = contentClient.generate(videoTitle, clip.candidate().text(),
                    platformsFor(clip));
            return Optional.of(new ClipContent(clipIndex, generated.summary(), generated.keywords(),
                    generated.tags(), generated.platforms()));
        } catch (RuntimeException exception) {
            log.warn("Skipping clip {} — content generation failed: {}", clipIndex, exception.getMessage());
            return Optional.empty();
        }
    }

    private List<String> platformsFor(ScoredCandidate clip) {
        List<String> suggested = clip.score().suggestedPlatforms();
        return suggested == null || suggested.isEmpty() ? DEFAULT_PLATFORMS : suggested;
    }

    private List<ScoredCandidate> readScored(Path scoredFile) {
        try {
            return objectMapper.readValue(Files.readString(scoredFile), new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scores " + scoredFile, exception);
        }
    }

    private void write(List<ClipContent> content, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(content));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write content to " + target, exception);
        }
    }
}
