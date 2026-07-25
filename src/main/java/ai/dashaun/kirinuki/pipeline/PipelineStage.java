package ai.dashaun.kirinuki.pipeline;

import ai.dashaun.kirinuki.video.Video;

public interface PipelineStage {

    PipelineStatus status();

    String artifact();

    void run(Video video);
}
