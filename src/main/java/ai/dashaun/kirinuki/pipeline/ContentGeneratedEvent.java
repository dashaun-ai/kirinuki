package ai.dashaun.kirinuki.pipeline;

import java.nio.file.Path;
import java.util.UUID;

public record ContentGeneratedEvent(UUID videoId, Path contentPath) {
}
