package ai.dashaun.kirinuki.content;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class ContentGenerationClient {

    private static final String SYSTEM = """
            You write posting metadata for a short-form clip cut from a long tech video, for platforms
            like TikTok, Reels, Shorts, LinkedIn and X. Work only from the transcript text you are given.

            Return:
            - summary: one or two sentences describing what the clip teaches, written to make a viewer want to watch.
            - keywords: 4-8 lowercase search/SEO terms a viewer might type to find this.
            - tags: 3-6 short lowercase hashtag words, without the leading '#'.

            Keep it specific to the actual content. Do not invent facts that are not in the transcript.
            """;

    private final ChatClient chatClient;

    public ContentGenerationClient(ChatClient.Builder chatClientBuilder, KirinukiPipelineProperties properties) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM)
                .defaultOptions(ChatOptions.builder().temperature(properties.content().temperature()))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Retryable(maxRetries = 2, delay = 500)
    public GeneratedContent generate(String videoTitle, String clipTranscript) {
        return chatClient.prompt()
                .user("Video title: %s%n%nClip transcript:%n%s".formatted(videoTitle, clipTranscript))
                .call()
                .entity(GeneratedContent.class);
    }
}
