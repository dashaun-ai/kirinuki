package ai.dashaun.kirinuki.review;

import java.util.List;

import ai.dashaun.kirinuki.content.PlatformVariant;
import jakarta.validation.constraints.NotNull;

public record EditClipContentRequest(
        @NotNull Long version,
        String summary,
        List<String> keywords,
        List<String> tags,
        List<PlatformVariant> platforms) {
}
