package ai.dashaun.kirinuki.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AudioFeatureExtractor {

    private final FfmpegClient ffmpegClient;
    private final ObjectMapper objectMapper;

    public AudioFeatureExtractor(FfmpegClient ffmpegClient, ObjectMapper objectMapper) {
        this.ffmpegClient = ffmpegClient;
        this.objectMapper = objectMapper;
    }

    public void extract(Path audio, Path target) {
        List<Silence> silences = ffmpegClient.detectSilence(audio);
        try {
            Files.writeString(target, objectMapper.writeValueAsString(new AudioFeatures(silences)));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write audio features to " + target, exception);
        }
    }
}
