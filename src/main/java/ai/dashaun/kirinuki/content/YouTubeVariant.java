package ai.dashaun.kirinuki.content;

import java.util.List;

public record YouTubeVariant(String platform, String title, String description, List<String> seoTags)
        implements PlatformVariant {
}
