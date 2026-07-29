package ai.dashaun.kirinuki.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import tools.jackson.databind.ObjectMapper;

@Component
public class SceneExtractor {

    private final FfmpegClient ffmpegClient;
    private final ObjectMapper objectMapper;

    public SceneExtractor(FfmpegClient ffmpegClient, ObjectMapper objectMapper) {
        this.ffmpegClient = ffmpegClient;
        this.objectMapper = objectMapper;
    }

    public void extract(Path source, Path target) {
        List<Double> scenes = ffmpegClient.detectScenes(source);
        try {
            Files.writeString(target, objectMapper.writeValueAsString(scenes));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write scenes to " + target, exception);
        }
    }
}
