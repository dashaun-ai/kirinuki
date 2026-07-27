package ai.dashaun.kirinuki.scoring;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class CandidateScoreClient {

    private static final String SYSTEM = """
            You rate a short-form clip candidate cut from a long tech video, for platforms like
            TikTok, Reels, Shorts, LinkedIn and X. Judge only the transcript text you are given.

            Score each dimension 0-10:
            - hook: does the opening line grab attention
            - educationalValue: does it teach something concrete
            - emotion: energy, humour, surprise, or tension
            - visualInterest: estimate from the words alone; there is no video signal yet, so stay near 5 unless the text clearly implies a demo or reveal
            - virality: would someone share or rewatch it
            Also give suggestedPlatforms (the platforms it best fits) and reason (one short sentence).
            Do NOT return an overall score.

            Be strict. Most candidates are average (3-5). Reserve high scores for genuinely strong moments.

            Example of a WEAK candidate — "Um, so yeah, let me just check my notes here, one second, okay
            where were we, right, so the thing is": {"hook":1,"educationalValue":1,"emotion":1,"visualInterest":4,"virality":1,"suggestedPlatforms":[],"reason":"Filler with no content or hook."}

            Example of a STRONG candidate — "Here's the mistake that cost us $50,000 in AWS bills overnight —
            and the one config flag that would have stopped it": {"hook":9,"educationalValue":8,"emotion":8,"visualInterest":5,"virality":9,"suggestedPlatforms":["TikTok","LinkedIn","X"],"reason":"Strong stakes-driven hook with a concrete, shareable lesson."}
            """;

    private final ChatClient chatClient;

    public CandidateScoreClient(ChatClient.Builder chatClientBuilder, KirinukiPipelineProperties properties) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM)
                .defaultOptions(ChatOptions.builder().temperature(properties.scoring().temperature()))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Retryable(maxRetries = 2, delay = 500)
    public CandidateScore score(Candidate candidate, String videoTitle) {
        return chatClient.prompt()
                .user("Video title: %s%n%nCandidate transcript:%n%s".formatted(videoTitle, candidate.text()))
                .call()
                .entity(CandidateScore.class);
    }
}
