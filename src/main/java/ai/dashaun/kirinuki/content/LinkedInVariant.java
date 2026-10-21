package ai.dashaun.kirinuki.content;

import java.util.List;

public record LinkedInVariant(String platform, String post, String carouselHook, List<String> hashtags,
        String callToAction) implements PlatformVariant {
}
