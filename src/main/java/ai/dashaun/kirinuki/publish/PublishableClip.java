package ai.dashaun.kirinuki.publish;

import java.nio.file.Path;
import java.util.List;

public record PublishableClip(int clipIndex, String summary, List<String> platforms, Path source, Path target) {
}
