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

            Style rules:
            - Never use em dashes. Use commas, periods or line breaks instead.
            - Do not use emojis unless one is genuinely necessary.
            - Keep grammar and spacing clean, with no stray spaces inside hashtags.
            - Write in second person ("you") to speak directly to the viewer.
            - Open with a bold claim or problem statement that hooks the viewer.
            - Use bullet points (with *) to break down what the video covers when listing multiple topics.
            - End with a question that invites engagement or a clear call to action.

            Return:
            - summary: one or two sentences describing what this video teaches, written to make a viewer want to watch.
            - keywords: 4-8 lowercase search/SEO terms a viewer might type to find this.
            - tags: 3-6 short lowercase hashtag words, without the leading '#'.
            - platforms: one entry per platform you are asked for, each with:
                - platform: the platform name exactly as given.
                - shape: one of x, youtube, linkedin, tiktok. Use tiktok for TikTok, Shorts and Reels.

                For shape "x":
                - tweet: a punchy post under 280 characters. One strong hook line.
                - threadSuggestion: a follow-up post that expands the idea.
                - hashtags: 3-6 lowercase hashtag words, without the leading '#'.

                For shape "youtube":
                - title: a clear, compelling video title.
                - description: a full multi-paragraph description. Open with a hook paragraph. List
                  topics covered with bullet points. Close with a call to action and engagement question.
                - seoTags: 3-6 lowercase search tags, without the leading '#'.

                For shape "linkedin":
                - post: 8-12 sentences in a professional but conversational tone. Open with an insight or
                  problem statement. Provide context on why this matters. List what the video covers with
                  bullet points. End with a question asking the reader about their experience.
                - carouselHook: a short hook for a carousel slide.
                - hashtags: 3-6 lowercase hashtag words, without the leading '#'.
                - callToAction: a short call to action.

                For shape "tiktok":
                - caption: 6-10 sentences. Open with a hook that calls out a common mistake or bold claim.
                  Explain the value of the video. List key topics covered using bullet points. Close with a
                  call to action or engagement question.
                - hookText: a one-line hook overlay for the video.
                - hashtags: 3-6 lowercase hashtag words, without the leading '#'.

            Keep it specific to the actual content. Do not invent facts that are not in the transcript.
            """;

    private static final String REGENERATE_SYSTEM = """
            You rewrite a single posting-metadata field for a short-form video cut from a longer tech video.
            Work only from the transcript text you are given. Refer to the content as "this video", never
            "this clip". Keep it polished, clear and professional with a light touch. Do not use emojis unless
            one is genuinely necessary, and keep no stray spaces inside hashtags. Return only the new value for
            the requested field, specific to the actual content and without inventing facts.
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

    @Retryable(maxRetries = 2, delay = 500)
    public String regenerateText(String videoTitle, String clipTranscript, String fieldInstruction) {
        return chatClient.prompt()
                .system(REGENERATE_SYSTEM)
                .user("Video title: %s%n%nRewrite %s.%n%nClip transcript:%n%s"
                        .formatted(videoTitle, fieldInstruction, clipTranscript))
                .call()
                .entity(RegeneratedText.class)
                .value();
    }

    @Retryable(maxRetries = 2, delay = 500)
    public List<String> regenerateList(String videoTitle, String clipTranscript, String fieldInstruction) {
        return chatClient.prompt()
                .system(REGENERATE_SYSTEM)
                .user("Video title: %s%n%nRewrite %s.%n%nClip transcript:%n%s"
                        .formatted(videoTitle, fieldInstruction, clipTranscript))
                .call()
                .entity(RegeneratedList.class)
                .value();
    }
}
