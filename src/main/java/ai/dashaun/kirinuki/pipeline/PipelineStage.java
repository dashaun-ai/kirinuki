package ai.dashaun.kirinuki.pipeline;

import ai.dashaun.kirinuki.video.Video;

public interface PipelineStage {

    PipelineStatus status();

    String artifact();

    void run(Video video);

    default void afterCommit(Video video) {
        // override to run work that must happen after the artifact is committed
    }
}
