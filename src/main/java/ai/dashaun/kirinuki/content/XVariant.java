package ai.dashaun.kirinuki.content;

import java.util.List;

public record XVariant(String platform, String tweet, String threadSuggestion, List<String> hashtags)
        implements PlatformVariant {
}
