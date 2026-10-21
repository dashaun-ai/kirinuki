package ai.dashaun.kirinuki.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "shape", visible = false)
@JsonSubTypes({
        @JsonSubTypes.Type(value = XVariant.class, name = "x"),
        @JsonSubTypes.Type(value = YouTubeVariant.class, name = "youtube"),
        @JsonSubTypes.Type(value = LinkedInVariant.class, name = "linkedin"),
        @JsonSubTypes.Type(value = TikTokVariant.class, name = "tiktok")
})
public sealed interface PlatformVariant permits XVariant, YouTubeVariant, LinkedInVariant, TikTokVariant {

    String platform();

    default String shape() {
        return switch (this) {
            case XVariant x -> "x";
            case YouTubeVariant y -> "youtube";
            case LinkedInVariant l -> "linkedin";
            case TikTokVariant t -> "tiktok";
        };
    }
}
