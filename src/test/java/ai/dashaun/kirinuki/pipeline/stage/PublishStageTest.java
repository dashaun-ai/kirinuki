package ai.dashaun.kirinuki.pipeline.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.TikTokVariant;
import ai.dashaun.kirinuki.content.XVariant;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.publish.PublishableClip;
import ai.dashaun.kirinuki.publish.Publisher;
import ai.dashaun.kirinuki.render.Clip;
import ai.dashaun.kirinuki.review.ClipReview;
import ai.dashaun.kirinuki.review.ClipReviewRepository;
import ai.dashaun.kirinuki.review.ReviewStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PublishStageTest {

    private static final UUID VIDEO_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String VIDEO_ID_STRING = VIDEO_ID.toString();

    @Mock
    private StorageService storageService;

    @Mock
    private ClipReviewRepository clipReviewRepository;

    @Mock
    private KirinukiPipelineProperties properties;

    @Mock
    private Publisher publisher;

    private final ObjectMapper objectMapper = new JsonMapper();

    private PublishStage stage;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        stage = new PublishStage(storageService, clipReviewRepository, properties, publisher, objectMapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishes_only_approved_clips() throws Exception {
        Video video = video();
        Path clipDir = tempDir.resolve("clips");
        Files.createDirectories(clipDir);
        Path clip1 = clipDir.resolve("clip-1.mp4");
        Path clip2 = clipDir.resolve("clip-2.mp4");
        Files.writeString(clip1, "one");
        Files.writeString(clip2, "two");
        Path manifestTemp = tempDir.resolve("published.part.json");
        Path publishDir = tempDir.resolve("published").resolve(VIDEO_ID_STRING);

        when(properties.publish()).thenReturn(new KirinukiPipelineProperties.Publish(tempDir.resolve("published").toString(), true));
        when(storageService.temporaryFor(VIDEO_ID_STRING, Artifacts.PUBLISHED)).thenReturn(manifestTemp);
        when(storageService.resolve(VIDEO_ID_STRING, Artifacts.CLIPS)).thenReturn(writeClips());
        when(storageService.resolve(VIDEO_ID_STRING, Artifacts.CLIP_DIRECTORY + "/clip-1.mp4")).thenReturn(clip1);
        when(clipReviewRepository.findByVideoIdAndStatusOrderByClipIndex(VIDEO_ID, ReviewStatus.APPROVED))
                .thenReturn(List.of(approvedRow(1)));

        stage.run(video);

        ArgumentCaptor<List<PublishableClip>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(eq(video), captor.capture(), eq(manifestTemp));
        List<PublishableClip> published = captor.getValue();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().clipIndex()).isEqualTo(1);
        assertThat(published.getFirst().summary()).isEqualTo("first summary");
        assertThat(published.getFirst().target()).isEqualTo(publishDir.resolve("clip-1.mp4"));
    }

    @Test
    void writes_empty_manifest_when_publishing_is_disabled() throws Exception {
        Video video = video();
        Path manifestTemp = tempDir.resolve("published.part.json");

        when(properties.publish()).thenReturn(new KirinukiPipelineProperties.Publish(tempDir.toString(), false));
        when(storageService.temporaryFor(VIDEO_ID_STRING, Artifacts.PUBLISHED)).thenReturn(manifestTemp);

        stage.run(video);

        verify(publisher, never()).publish(any(), any(), any());
        assertThat(manifestTemp).hasContent("[]");
    }

    private Video video() {
        return new Video(VIDEO_ID, "abc123", "https://example.com", "Test", 10, null, PipelineStatus.READY_TO_PUBLISH,
                Instant.now(), null);
    }

    private ClipReview approvedRow(int clipIndex) {
        ClipContent content = new ClipContent(clipIndex, "first summary", List.of(), List.of(),
                List.of(new TikTokVariant("TikTok", "", "", List.of()), new XVariant("X", "", "", List.of())));
        return new ClipReview(UUID.randomUUID(), VIDEO_ID, clipIndex, ReviewStatus.APPROVED,
                objectMapper.writeValueAsString(content), Instant.now(), Instant.now(), null);
    }

    private Path writeClips() throws Exception {
        Path clipsFile = tempDir.resolve(Artifacts.CLIPS);
        Files.writeString(clipsFile, objectMapper.writeValueAsString(List.of(
                new Clip(1, 0.0, 10.0, 80, "strong hook", "clip-1.mp4"),
                new Clip(2, 10.0, 20.0, 70, "good hook", "clip-2.mp4"))));
        return clipsFile;
    }
}
