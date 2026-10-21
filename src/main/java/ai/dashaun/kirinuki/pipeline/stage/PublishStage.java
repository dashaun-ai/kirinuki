package ai.dashaun.kirinuki.pipeline.stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.content.ClipContent;
import ai.dashaun.kirinuki.content.PlatformVariant;
import ai.dashaun.kirinuki.pipeline.Artifacts;
import ai.dashaun.kirinuki.pipeline.PipelineStage;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.publish.PublishableClip;
import ai.dashaun.kirinuki.publish.Publisher;
import ai.dashaun.kirinuki.render.Clip;
import ai.dashaun.kirinuki.review.ClipReview;
import ai.dashaun.kirinuki.review.ClipReviewRepository;
import ai.dashaun.kirinuki.review.ReviewStatus;
import ai.dashaun.kirinuki.storage.StorageService;
import ai.dashaun.kirinuki.video.Video;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class PublishStage implements PipelineStage {

    private final StorageService storageService;
    private final ClipReviewRepository clipReviewRepository;
    private final KirinukiPipelineProperties properties;
    private final Publisher publisher;
    private final ObjectMapper objectMapper;

    PublishStage(StorageService storageService, ClipReviewRepository clipReviewRepository,
            KirinukiPipelineProperties properties, Publisher publisher, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.clipReviewRepository = clipReviewRepository;
        this.properties = properties;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public PipelineStatus status() {
        return PipelineStatus.READY_TO_PUBLISH;
    }

    @Override
    public String artifact() {
        return Artifacts.PUBLISHED;
    }

    @Override
    public void run(Video video) {
        String videoId = video.getId().toString();
        Path target = storageService.temporaryFor(videoId, Artifacts.PUBLISHED);
        if (!properties.publish().enabled()) {
            writeEmptyManifest(target);
            return;
        }
        List<ClipReview> approved = clipReviewRepository.findByVideoIdAndStatusOrderByClipIndex(video.getId(),
                ReviewStatus.APPROVED);
        Map<Integer, Clip> clips = clipsByIndex(videoId);
        Path publishDir = Path.of(properties.publish().directory()).resolve(videoId);
        List<PublishableClip> publishable = approved.stream()
                .map(row -> toPublishableClip(row, clips, publishDir))
                .toList();
        publisher.publish(video, publishable, target);
    }

    private void writeEmptyManifest(Path target) {
        try {
            Files.writeString(target, "[]");
        } catch (IOException exception) {
            throw new KirinukiException("Could not write empty publish manifest to " + target, exception);
        }
    }

    private Map<Integer, Clip> clipsByIndex(String videoId) {
        Path clipsFile = storageService.resolve(videoId, Artifacts.CLIPS);
        try {
            List<Clip> clips = objectMapper.readValue(Files.readString(clipsFile), new TypeReference<List<Clip>>() {
            });
            return clips.stream().collect(Collectors.toMap(Clip::index, clip -> clip));
        } catch (IOException exception) {
            throw new KirinukiException("Could not read clips manifest " + clipsFile, exception);
        }
    }

    private PublishableClip toPublishableClip(ClipReview row, Map<Integer, Clip> clips, Path publishDir) {
        Clip clip = clips.get(row.getClipIndex());
        if (clip == null) {
            throw new KirinukiException("No rendered clip for approved index " + row.getClipIndex());
        }
        ClipContent content = readContent(row);
        Path source = storageService.resolve(row.getVideoId().toString(),
                Artifacts.CLIP_DIRECTORY + "/" + clip.video());
        Path target = publishDir.resolve(clip.video());
        return new PublishableClip(row.getClipIndex(), content.summary(),
                content.platforms().stream().map(PlatformVariant::platform).toList(), source, target);
    }

    private ClipContent readContent(ClipReview row) {
        return objectMapper.readValue(row.getContent(), ClipContent.class);
    }
}
