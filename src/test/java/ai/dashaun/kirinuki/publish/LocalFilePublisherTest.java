package ai.dashaun.kirinuki.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class LocalFilePublisherTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @TempDir
    Path tempDir;

    @Test
    void publishes_approved_clips_and_writes_manifest() throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path publishDir = tempDir.resolve("published");
        Files.createDirectories(sourceDir);
        Path clip1 = sourceDir.resolve("clip-1.mp4");
        Path clip2 = sourceDir.resolve("clip-2.mp4");
        Files.writeString(clip1, "clip-one");
        Files.writeString(clip2, "clip-two");

        Video video = video(UUID.randomUUID());
        Path clipTarget1 = publishDir.resolve(video.getId().toString()).resolve("clip-1.mp4");
        Path clipTarget2 = publishDir.resolve(video.getId().toString()).resolve("clip-2.mp4");
        List<PublishableClip> clips = List.of(
                new PublishableClip(1, "first summary", List.of("TikTok"), clip1, clipTarget1),
                new PublishableClip(2, "second summary", List.of("X"), clip2, clipTarget2));
        Path manifest = tempDir.resolve("published.json");

        publisher(publishDir.toString(), true).publish(video, clips, manifest);

        assertThat(clipTarget1).hasContent("clip-one");
        assertThat(clipTarget2).hasContent("clip-two");
        assertThat(manifest).exists();
        List<PublishableClip> published = objectMapper.readValue(Files.readString(manifest),
                new TypeReference<List<PublishableClip>>() {
                });
        assertThat(published).hasSize(2);
        assertThat(published.getFirst().clipIndex()).isEqualTo(1);
        assertThat(published.getLast().platforms()).containsExactly("X");
    }

    @Test
    void skips_publishing_when_disabled() throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path publishDir = tempDir.resolve("published");
        Files.createDirectories(sourceDir);
        Path clip = sourceDir.resolve("clip-1.mp4");
        Files.writeString(clip, "clip-one");

        Video video = video(UUID.randomUUID());
        Path clipTarget = publishDir.resolve(video.getId().toString()).resolve("clip-1.mp4");
        List<PublishableClip> clips = List.of(new PublishableClip(1, "summary", List.of("TikTok"), clip, clipTarget));
        Path manifest = tempDir.resolve("published.json");

        publisher(publishDir.toString(), false).publish(video, clips, manifest);

        assertThat(clipTarget).doesNotExist();
        assertThat(manifest).hasContent("[]");
    }

    private LocalFilePublisher publisher(String directory, boolean enabled) {
        return new LocalFilePublisher(properties(directory, enabled), objectMapper);
    }

    private KirinukiPipelineProperties properties(String directory, boolean enabled) {
        return new Binder(new MapConfigurationPropertySource(
                java.util.Map.of("kirinuki.pipeline.publish.directory", directory,
                        "kirinuki.pipeline.publish.enabled", String.valueOf(enabled))))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }

    private Video video(UUID id) {
        return new Video(id, "abc123", "https://example.com", "Test", 10, null, PipelineStatus.READY_TO_PUBLISH,
                Instant.now(), null);
    }
}
