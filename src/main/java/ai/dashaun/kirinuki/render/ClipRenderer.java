package ai.dashaun.kirinuki.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClipRenderer {

    private static final Logger log = LoggerFactory.getLogger(ClipRenderer.class);

    private final ObjectMapper objectMapper;
    private final TranscriptReader transcriptReader;
    private final SubtitleWriter subtitleWriter;
    private final FfmpegClient ffmpegClient;
    private final KirinukiPipelineProperties properties;

    public ClipRenderer(ObjectMapper objectMapper, TranscriptReader transcriptReader, SubtitleWriter subtitleWriter,
            FfmpegClient ffmpegClient, KirinukiPipelineProperties properties) {
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.subtitleWriter = subtitleWriter;
        this.ffmpegClient = ffmpegClient;
        this.properties = properties;
    }

    public void render(Path source, Path transcript, Path scoredFile, Path clipDirectory, Path target) {
        List<ScoredCandidate> scored = readScored(scoredFile);
        List<Word> words = transcriptReader.readWords(transcript);
        createDirectory(clipDirectory);

        List<Clip> clips = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            clips.add(renderOne(source, words, scored.get(index), index + 1, clipDirectory));
        }
        log.info("Rendered {} clips into {}", clips.size(), clipDirectory);
        write(clips, target);
    }

    private Clip renderOne(Path source, List<Word> words, ScoredCandidate scored, int number, Path clipDirectory) {
        var candidate = scored.candidate();
        double leadIn = Math.min(properties.render().leadIn().toMillis() / 1000.0,
                gapBefore(words, candidate.firstWordIndex()));
        double tail = Math.min(properties.render().tail().toMillis() / 1000.0,
                gapAfter(words, candidate.lastWordIndex()));
        double start = Math.max(0, candidate.start() - leadIn);
        double end = candidate.end() + tail;
        Path subtitles = clipDirectory.resolve("clip-%d.ass".formatted(number));
        Path video = clipDirectory.resolve("clip-%d.mp4".formatted(number));

        subtitleWriter.write(words, start, end, subtitles);
        ffmpegClient.renderVertical(source, start, end - start, subtitles, video);

        return new Clip(number, start, end, scored.overallScore(), scored.score().reason(),
                video.getFileName().toString());
    }

    private double gapBefore(List<Word> words, int firstWordIndex) {
        if (firstWordIndex <= 0) {
            return Double.MAX_VALUE;
        }
        return words.get(firstWordIndex).start() - words.get(firstWordIndex - 1).end();
    }

    private double gapAfter(List<Word> words, int lastWordIndex) {
        if (lastWordIndex + 1 >= words.size()) {
            return Double.MAX_VALUE;
        }
        return words.get(lastWordIndex + 1).start() - words.get(lastWordIndex).end();
    }

    private List<ScoredCandidate> readScored(Path scoredFile) {
        try {
            return objectMapper.readValue(Files.readString(scoredFile), new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scores " + scoredFile, exception);
        }
    }

    private void createDirectory(Path clipDirectory) {
        try {
            Files.createDirectories(clipDirectory);
        } catch (IOException exception) {
            throw new KirinukiException("Could not create clip directory " + clipDirectory, exception);
        }
    }

    private void write(List<Clip> clips, Path target) {
        try {
            Files.writeString(target, objectMapper.writeValueAsString(clips));
        } catch (IOException exception) {
            throw new KirinukiException("Could not write clip manifest to " + target, exception);
        }
    }
}
