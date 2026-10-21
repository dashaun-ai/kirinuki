package ai.dashaun.kirinuki.publish;

import java.nio.file.Path;
import java.util.List;

import ai.dashaun.kirinuki.video.Video;

public interface Publisher {

    void publish(Video video, List<PublishableClip> clips, Path target);
}
