package ai.dashaun.kirinuki.video;

import java.time.Instant;
import java.util.UUID;

import ai.dashaun.kirinuki.pipeline.PipelineStatus;

public record VideoResponse(
        UUID id,
        String youtubeId,
        String sourceUrl,
        String title,
        int durationSeconds,
        String uploader,
        PipelineStatus status,
        Instant ingestedAt,
        String lastError) {
}
