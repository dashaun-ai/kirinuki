package ai.dashaun.kirinuki.pipeline;

public enum PipelineStatus {
    DOWNLOADING(true),
    UPLOADED(true),
    MEDIA_PREPARATION(true),
    FEATURE_EXTRACTION(true),
    WAITING_FOR_FEATURES(true),
    CANDIDATE_GENERATION(true),
    AI_ANALYSIS(true),
    CLIP_RENDERING(true),
    METADATA_GENERATION(true),
    READY_FOR_REVIEW(false),
    READY_TO_PUBLISH(true),
    PUBLISHED(false);

    private final boolean resumable;

    PipelineStatus(boolean resumable) {
        this.resumable = resumable;
    }

    public boolean isResumable() {
        return resumable;
    }
}
