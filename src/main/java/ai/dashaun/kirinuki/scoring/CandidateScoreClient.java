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
            - virality: would someone share or rewatch it
            Also give suggestedPlatforms (the platforms it best fits) and reason (one short sentence).
            Do NOT return an overall score.

            Calibrate against this scale:
            - 1-3: filler, rambling, or setup a viewer would skip
            - 4-5: below par; a real point, weakly delivered or incomplete
            - 6-7: solid; a clear, useful moment worth publishing as it stands
            - 8-9: exceptional; a standout hook or a genuinely surprising insight
            - 10: the single best moment in the whole video

            Do not cluster everything in the middle. A clear, well-explained teaching moment is a 6 or 7,
            not a 5. Reserve 4 and below for moments that are actually weak, not merely unglamorous.

            Example of a WEAK candidate — "Um, so yeah, let me just check my notes here, one second, okay
            where were we, right, so the thing is": {"hook":1,"educationalValue":1,"emotion":1,"virality":1,"suggestedPlatforms":[],"reason":"Filler with no content or hook."}

            Example of a SOLID candidate — "The reason your bean is null here is that Spring hasn't finished
            wiring the context yet, so constructor injection fixes what field injection cannot": {"hook":6,"educationalValue":7,"emotion":5,"virality":6,"suggestedPlatforms":["LinkedIn","Shorts"],"reason":"Clear explanation of a common bug with a concrete fix."}

            Example of a STRONG candidate — "Here's the mistake that cost us $50,000 in AWS bills overnight —
            and the one config flag that would have stopped it": {"hook":9,"educationalValue":8,"emotion":8,"virality":9,"suggestedPlatforms":["TikTok","LinkedIn","X"],"reason":"Strong stakes-driven hook with a concrete, shareable lesson."}
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
