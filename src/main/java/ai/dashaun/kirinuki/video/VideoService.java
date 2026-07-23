package ai.dashaun.kirinuki.video;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.DuplicateVideoException;
import ai.dashaun.kirinuki.common.InvalidVideoUrlException;
import ai.dashaun.kirinuki.common.VideoNotFoundException;
import ai.dashaun.kirinuki.pipeline.PipelineOrchestrator;
import ai.dashaun.kirinuki.pipeline.PipelineStatus;
import ai.dashaun.kirinuki.storage.StorageService;

@Slf4j
@Service
public class VideoService {

    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private final VideoRepository videoRepository;
    private final YtDlpClient ytDlpClient;
    private final StorageService storageService;
    private final PipelineOrchestrator pipelineOrchestrator;

    public VideoService(VideoRepository videoRepository, YtDlpClient ytDlpClient, StorageService storageService,
            PipelineOrchestrator pipelineOrchestrator) {
        this.videoRepository = videoRepository;
        this.ytDlpClient = ytDlpClient;
        this.storageService = storageService;
        this.pipelineOrchestrator = pipelineOrchestrator;
    }

    public VideoResponse ingest(String url) {
        String youtubeId = extractYoutubeId(url);
        if (videoRepository.existsByYoutubeId(youtubeId)) {
            throw new DuplicateVideoException(youtubeId);
        }

        // Metadata stays synchronous so an unavailable or private video fails the request outright
        // and no row is written (FR-ING-4); the download itself is a pipeline stage.
        YouTubeMetadata metadata = ytDlpClient.fetchMetadata(url);
        UUID videoId = UUID.randomUUID();

        Video video = new Video(videoId, metadata.youtubeId(), url, metadata.title(), metadata.durationSeconds(),
                metadata.uploader(), PipelineStatus.DOWNLOADING, Instant.now(), null);
        VideoResponse response = toResponse(videoRepository.save(video));
        pipelineOrchestrator.startAsync(videoId);
        return response;
    }

    public VideoResponse findById(UUID videoId) {
        log.info("Finding video {}", videoId);
        return videoRepository.findById(videoId)
                .map(this::toResponse)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
    }

    private String extractYoutubeId(String url) {
        Matcher matcher = YOUTUBE_ID.matcher(url);
        if (!matcher.find()) {
            throw new InvalidVideoUrlException(url);
        }
        return matcher.group(1);
    }

    private VideoResponse toResponse(Video video) {
        return new VideoResponse(
                video.getId(),
                video.getYoutubeId(),
                video.getSourceUrl(),
                video.getTitle(),
                video.getDurationSeconds(),
                video.getUploader(),
                video.getStatus(),
                video.getIngestedAt(),
                video.getLastError());
    }
}
