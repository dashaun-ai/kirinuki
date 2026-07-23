package ai.dashaun.kirinuki.pipeline;

import ai.dashaun.kirinuki.video.Video;

public interface PipelineStage {

    /** Skipped when this already exists, which is what makes a re-drive resume rather than restart. */
    String artifact();

    PipelineStatus status();

    void run(Video video);

    default PipelineStatus completedStatus() {
        return status();
    }
}
