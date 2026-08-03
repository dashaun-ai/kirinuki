package ai.dashaun.kirinuki.content;

import java.util.List;

public record PlatformVariant(String platform, String title, String caption, List<String> hashtags,
        String callToAction) {
}
