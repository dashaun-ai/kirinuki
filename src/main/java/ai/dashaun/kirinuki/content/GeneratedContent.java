package ai.dashaun.kirinuki.content;

import java.util.List;

public record GeneratedContent(String summary, List<String> keywords, List<String> tags,
        List<PlatformVariant> platforms) {
}
