package ai.dashaun.kirinuki.review;

import java.util.List;

import ai.dashaun.kirinuki.content.PlatformVariant;

public record EditClipContentRequest(
        String summary,
        List<String> keywords,
        List<String> tags,
        List<PlatformVariant> platforms) {
}
