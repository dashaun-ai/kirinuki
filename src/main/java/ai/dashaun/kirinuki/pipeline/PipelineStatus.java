package ai.dashaun.kirinuki.pipeline;

import java.util.List;

public enum PipelineStatus {
    DOWNLOADING,
    UPLOADED,
    MEDIA_PREPARATION,
    FEATURE_EXTRACTION,
    WAITING_FOR_FEATURES,
    CANDIDATE_GENERATION,
    AI_ANALYSIS,
    CLIP_RENDERING,
    METADATA_GENERATION,
    READY_FOR_REVIEW,
    READY_TO_PUBLISH,
    PUBLISHED;

    /** Non-terminal and not parked at human review, so a restart should re-drive them (FR-WF-6). */
    public static final List<PipelineStatus> RESUMABLE = List.of(
            DOWNLOADING,
            UPLOADED,
            MEDIA_PREPARATION,
            FEATURE_EXTRACTION,
            WAITING_FOR_FEATURES,
            CANDIDATE_GENERATION,
            AI_ANALYSIS,
            CLIP_RENDERING,
            METADATA_GENERATION);
}
