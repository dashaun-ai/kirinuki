package ai.dashaun.kirinuki.edit;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.vision.Scene;

@Component
public class EditPlanClient {

    private static final String SYSTEM = """
            You are a video editor planning a short-form tech clip. The speaker's original footage is the spine.
            Your job is to propose a paced edit plan: a sequence of shots that cut in around the footage when
            the transcript calls for emphasis, and default to showing the speaker the rest of the time.

            You are given a scene analysis that tells you who is speaking, when the screen shows code, and
            how many faces are visible. Use it to choose the right visual treatment for each beat.

            Output a single JSON object with this exact shape:
            {
              "shots": [
                {
                  "in": 0.0,
                  "out": 3.5,
                  "block": "SLAM",
                  "headline": "Virtual threads",
                  "subline": null,
                  "lines": null,
                  "logos": null,
                  "imageQuery": null,
                  "zoom": null,
                  "transition": null,
                  "type": "KINETIC",
                  "speaker": "A",
                  "speakers": ["A"],
                  "kinetic": {
                    "style": "timeline",
                    "title": "Java evolution",
                    "items": [
                      {"label": "Java 8", "value": "2014", "detail": null},
                      {"label": "Java 9", "value": "2017", "detail": null}
                    ]
                  },
                  "focus": null
                }
              ],
              "emphasis": [
                {"word": "Virtual threads", "at": 0.0, "kind": "PUNCH"}
              ]
            }

            Shot type (choose one per shot):
            - SPEAKER: one speaker talking, no relevant screen content. Use SOURCE block.
            - DIALOGUE_2UP: two speakers taking turns. Use SOURCE block and switch between speakers.
            - DIALOGUE_3UP: three speakers. Use SOURCE block and rotate the active speaker.
            - CODE: code/terminal fills the frame. Use CODE or TERMINAL block.
            - MIXED: speaker visible while explaining code. Use SOURCE block; focus marks the screen region.
            - KINETIC: sequence, history, list, or abstract concept. Use SLAM, HIGHLIGHT, COUNTER, or DIAGRAM block and provide a kinetic spec.

            Block catalogue. Only use these block values:
            - SOURCE: the speaker's real clip. Default when showing code or explaining. Use zoom to punch in.
            - SLAM: one huge word/phrase with a hit. headline required, 3 words or fewer.
            - HIGHLIGHT: sentence with a marker-box on the key word. headline + subline required.
            - TERMINAL: window chrome typing commands/output. lines required.
            - CODE: syntax-highlighted snippet typing in. lines required.
            - LOGO_WALL: grid of ecosystem logos popping in. logos required.
            - DIAGRAM: hub-and-spoke with packets and success ticks. headline + lines (node labels) required.
            - STAMP: verdict stamp over a dimmed frame. headline required.
            - COUNTER: number counting up to a stat. headline (the number) + subline required.
            - IMAGE: photo/screenshot with slow push-in. imageQuery required, headline optional.
            - OUTRO: stacked final line + handle + mascot. headline required. Use for the last 2-3 seconds.

            Rules:
            - Every shot must be between 0.8 and 6 seconds, except SOURCE which may run up to 12 seconds.
            - Non-SOURCE shots must not cover more than 60%% of the clip duration.
            - Shots must cover the full clip from 0 to duration with no gaps or overlaps.
            - The last shot must be OUTRO.
            - Prefer CODE or MIXED whenever the screen shows relevant code, terminal, or demo.
            - Use KINETIC for lists, version histories, comparisons, or any concept that benefits from motion graphics.
            - Use DIALOGUE_2UP / DIALOGUE_3UP when the scene analysis shows multiple active speakers in that beat.
            - Keep text short and readable on a 1080x1920 vertical canvas.
            - Do not invent facts not in the transcript.
            - If the screen has no code, never use CODE or MIXED type.

            Emphasis rules:
            - PUNCH: a word that should hit hard, often paired with a SLAM or HIGHLIGHT.
            - PAUSE: a moment to breathe, usually before a transition.
            - The "at" timestamp must fall on a real word start from the transcript.
            """;

    private final ChatClient chatClient;
    private final KirinukiPipelineProperties properties;

    public EditPlanClient(ChatClient.Builder chatClientBuilder, KirinukiPipelineProperties properties) {
        this.properties = properties;
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Retryable(maxRetries = 2, delay = 500)
    public EditPlan plan(String videoTitle, String clipTranscript, double clipDuration, Scene scene,
            List<ThemeTopic> topics, List<String> vocabulary) {
        StringBuilder user = new StringBuilder();
        user.append("Video title: ").append(videoTitle).append("\n");
        user.append("Clip duration: ").append(clipDuration).append(" seconds\n");
        if (scene != null) {
            user.append("Speakers: ").append(scene.speakers().size()).append("\n");
            user.append("Has code on screen: ").append(scene.hasCode()).append("\n");
            user.append("Screen region: ").append(scene.screenRegion()).append("\n");
            user.append("Screen changes: ").append(scene.screenChanges()).append("\n");
            user.append("Active speakers:\n");
            for (var activity : scene.activeSpeaker()) {
                user.append("- ").append(activity.speakerId()).append(" ").append(activity.from()).append("-")
                        .append(activity.to()).append("\n");
            }
        }
        if (!topics.isEmpty()) {
            user.append("Matching theme topics:\n");
            for (ThemeTopic topic : topics) {
                user.append("- keywords: ").append(String.join(", ", topic.match())).append("\n");
                if (!topic.prefer().isEmpty()) {
                    user.append("  prefer blocks: ").append(String.join(", ", topic.prefer())).append("\n");
                }
                if (!topic.logos().isEmpty()) {
                    user.append("  logos: ").append(String.join(", ", topic.logos())).append("\n");
                }
            }
        }
        if (!vocabulary.isEmpty()) {
            user.append("Known vocabulary: ").append(String.join(", ", vocabulary)).append("\n");
        }
        user.append("\nClip transcript:\n").append(clipTranscript).append("\n");

        return chatClient.prompt()
                .options(ChatOptions.builder()
                        .model(properties.edit().planModel())
                        .temperature(properties.edit().planTemperature())
                        .maxTokens(properties.edit().planMaxTokens()))
                .user(user.toString())
                .call()
                .entity(EditPlan.class);
    }
}
