package ai.dashaun.kirinuki.content;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class ContentGenerationClient {

    private static final String SYSTEM = """
            You write posting metadata for a short-form video cut from a longer tech video. Work only from
            the transcript text you are given. Always refer to the content as "this video", never "this clip".

            Write captions that are polished, clear and professional, with a light and engaging touch. Do not
            use emojis unless one is genuinely necessary. Keep grammar and spacing clean, with no stray
            spaces inside hashtags.

            Return:
            - summary: one or two sentences describing what this video teaches, written to make a viewer want to watch.
            - keywords: 4-8 lowercase search/SEO terms a viewer might type to find this.
            - tags: 3-6 short lowercase hashtag words, without the leading '#'.
            - platforms: one entry per platform you are asked for, each with:
                - platform: the platform name exactly as given.
                - title: a clear, compelling headline (for YouTube this is the video title).
                - caption: the post body tuned to that platform (concise and lively for TikTok, Reels and
                  Shorts, professional for LinkedIn, under 280 characters for X, a full description for YouTube).
                - hashtags: 3-6 lowercase hashtag words, without the leading '#'.
                - callToAction: a short call to action (mainly for LinkedIn), or an empty string.

            Keep it specific to the actual content. Do not invent facts that are not in the transcript.
            """;

    private final ChatClient chatClient;

    public ContentGenerationClient(ChatClient.Builder chatClientBuilder, KirinukiPipelineProperties properties) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM)
                .defaultOptions(ChatOptions.builder()
                        .temperature(properties.content().temperature())
                        .maxTokens(properties.content().maxTokens()))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Retryable(maxRetries = 2, delay = 500)
    public GeneratedContent generate(String videoTitle, String clipTranscript, List<String> platforms) {
        return chatClient.prompt()
                .user("Video title: %s%n%nPlatforms: %s%n%nClip transcript:%n%s"
                        .formatted(videoTitle, String.join(", ", platforms), clipTranscript))
                .call()
                .entity(GeneratedContent.class);
    }
}
