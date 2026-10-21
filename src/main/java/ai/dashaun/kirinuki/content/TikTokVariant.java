package ai.dashaun.kirinuki.content;

import java.util.List;

public record TikTokVariant(String platform, String caption, String hookText, List<String> hashtags)
        implements PlatformVariant {
}
