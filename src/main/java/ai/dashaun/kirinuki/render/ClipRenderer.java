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

    public ClipRenderer(ObjectMapper objectMapper, TranscriptReader transcriptReader, SubtitleWriter subtitleWriter,
            FfmpegClient ffmpegClient) {
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.subtitleWriter = subtitleWriter;
        this.ffmpegClient = ffmpegClient;
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
        double start = scored.candidate().start();
        double end = scored.candidate().end();
        Path subtitles = clipDirectory.resolve("clip-%d.ass".formatted(number));
        Path video = clipDirectory.resolve("clip-%d.mp4".formatted(number));
        Path thumbnail = clipDirectory.resolve("clip-%d.jpg".formatted(number));

        subtitleWriter.write(words, start, end, subtitles);
        ffmpegClient.renderVertical(source, start, end - start, subtitles, video);
        ffmpegClient.thumbnail(video, (end - start) / 2, thumbnail);

        return new Clip(number, start, end, scored.overallScore(), scored.score().reason(),
                video.getFileName().toString(), thumbnail.getFileName().toString());
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
