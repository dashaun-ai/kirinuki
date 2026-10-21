package ai.dashaun.kirinuki.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PlatformVariantTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void should_round_trip_x_variant() throws Exception {
        XVariant original = new XVariant("X", "tweet text", "thread text", List.of("java", "spring"));

        PlatformVariant deserialized = objectMapper.readValue(objectMapper.writeValueAsString(original),
                PlatformVariant.class);

        assertThat(deserialized).isInstanceOf(XVariant.class).satisfies(v -> assertThat(v).isEqualTo(original));
    }

    @Test
    void should_round_trip_youtube_variant() throws Exception {
        YouTubeVariant original = new YouTubeVariant("YouTube", "title text", "description text",
                List.of("spring boot", "java"));

        PlatformVariant deserialized = objectMapper.readValue(objectMapper.writeValueAsString(original),
                PlatformVariant.class);

        assertThat(deserialized).isInstanceOf(YouTubeVariant.class).satisfies(v -> assertThat(v).isEqualTo(original));
    }

    @Test
    void should_round_trip_linkedin_variant() throws Exception {
        LinkedInVariant original = new LinkedInVariant("LinkedIn", "post text", "carousel hook",
                List.of("java", "spring"), "follow for more");

        PlatformVariant deserialized = objectMapper.readValue(objectMapper.writeValueAsString(original),
                PlatformVariant.class);

        assertThat(deserialized).isInstanceOf(LinkedInVariant.class).satisfies(v -> assertThat(v).isEqualTo(original));
    }

    @Test
    void should_round_trip_tiktok_variant() throws Exception {
        TikTokVariant original = new TikTokVariant("TikTok", "caption text", "hook text", List.of("java", "spring"));

        PlatformVariant deserialized = objectMapper.readValue(objectMapper.writeValueAsString(original),
                PlatformVariant.class);

        assertThat(deserialized).isInstanceOf(TikTokVariant.class).satisfies(v -> assertThat(v).isEqualTo(original));
    }

    @Test
    void should_deserialize_shorts_as_tiktok_shape() throws Exception {
        String json = """
                {"shape":"tiktok","platform":"Shorts","caption":"c","hookText":"h","hashtags":["j"]}""";

        PlatformVariant deserialized = objectMapper.readValue(json, PlatformVariant.class);

        assertThat(deserialized).isInstanceOf(TikTokVariant.class)
                .extracting(PlatformVariant::platform)
                .isEqualTo("Shorts");
    }
}
