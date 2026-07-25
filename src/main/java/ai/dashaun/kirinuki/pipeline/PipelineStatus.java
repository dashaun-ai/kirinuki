package ai.dashaun.kirinuki.pipeline;

public enum PipelineStatus {
    DOWNLOADING(true),
    MEDIA_PREPARATION(true),
    FEATURE_EXTRACTION(true),
    CANDIDATE_GENERATION(true),
    AI_ANALYSIS(true),
    CLIP_RENDERING(true),
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

    public PipelineStatus next() {
        PipelineStatus[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : this;
    }
}
