package ai.dashaun.kirinuki.pipeline;

import ai.dashaun.kirinuki.video.Video;

public interface PipelineStage {
    String artifact();

    PipelineStatus status();

    void run(Video video);

    default PipelineStatus completedStatus() {
        return status();
    }
}
