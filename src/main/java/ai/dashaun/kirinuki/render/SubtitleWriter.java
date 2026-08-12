package ai.dashaun.kirinuki.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class SubtitleWriter {

    private final KirinukiPipelineProperties properties;

    public SubtitleWriter(KirinukiPipelineProperties properties) {
        this.properties = properties;
    }

    public void write(List<Word> words, double clipStart, double clipEnd, Path target) {
        List<Word> inClip = words.stream()
                .filter(word -> word.end() > clipStart && word.start() < clipEnd)
                .toList();

        boolean uppercase = properties.render().subtitleUppercase();
        StringBuilder ass = new StringBuilder(header());
        for (List<Word> caption : group(inClip)) {
            double start = Math.max(0, caption.getFirst().start() - clipStart);
            double end = Math.min(clipEnd - clipStart, caption.getLast().end() - clipStart);
            String text = caption.stream().map(Word::text).reduce("", String::concat).strip();
            if (uppercase) {
                text = text.toUpperCase();
            }
            ass.append("Dialogue: 0,%s,%s,Default,,0,0,0,,%s%n".formatted(timestamp(start), timestamp(end),
                    text.replace("\n", " ")));
        }
        try {
            Files.writeString(target, ass.toString());
        } catch (IOException exception) {
            throw new KirinukiException("Could not write subtitles to " + target, exception);
        }
    }

    private List<List<Word>> group(List<Word> words) {
        int perCaption = properties.render().wordsPerCaption();
        List<List<Word>> captions = new ArrayList<>();
        for (int index = 0; index < words.size(); index += perCaption) {
            captions.add(words.subList(index, Math.min(index + perCaption, words.size())));
        }
        return captions;
    }

    private String header() {
        KirinukiPipelineProperties.Render render = properties.render();
        return """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: %d
                PlayResY: %d

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,%s,%d,&H00FFFFFF,&H00000000,&H80000000,-1,4,2,2,60,60,%d,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                """.formatted(render.width(), render.height(), render.subtitleFont(), render.subtitleSize(),
                render.subtitleMarginBottom());
    }

    private String timestamp(double seconds) {
        int total = (int) seconds;
        int centis = (int) Math.round((seconds - total) * 100);
        return "%d:%02d:%02d.%02d".formatted(total / 3600, (total % 3600) / 60, total % 60, centis);
    }
}
