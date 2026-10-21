package ai.dashaun.kirinuki.publish;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalFilePublisher implements Publisher {

    private final KirinukiPipelineProperties properties;
    private final ObjectMapper objectMapper;

    public LocalFilePublisher(KirinukiPipelineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Video video, List<PublishableClip> clips, Path target) {
        if (!properties.publish().enabled()) {
            writeManifest(target, List.of());
            return;
        }
        Path publishDir = Path.of(properties.publish().directory()).resolve(video.getId().toString());
        try {
            Files.createDirectories(publishDir);
            for (PublishableClip clip : clips) {
                Files.copy(clip.source(), clip.target(), StandardCopyOption.REPLACE_EXISTING);
            }
            writeManifest(target, clips);
        } catch (IOException exception) {
            throw new KirinukiException("Could not publish video " + video.getId(), exception);
        }
    }

    private void writeManifest(Path target, List<PublishableClip> clips) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(clips));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write publish manifest to " + target, exception);
        }
    }
}
