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
import ai.dashaun.kirinuki.media.AudioFeatures;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.media.Silence;
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
    private final BoundaryRefiner boundaryRefiner;

    public ClipRenderer(ObjectMapper objectMapper, TranscriptReader transcriptReader, SubtitleWriter subtitleWriter,
            FfmpegClient ffmpegClient, BoundaryRefiner boundaryRefiner) {
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.subtitleWriter = subtitleWriter;
        this.ffmpegClient = ffmpegClient;
        this.boundaryRefiner = boundaryRefiner;
    }

    public void render(Path source, Path transcript, Path scoredFile, Path scenesFile, Path audioFeaturesFile,
            Path clipDirectory, Path target) {
        List<ScoredCandidate> scored = readScored(scoredFile);
        List<Word> words = transcriptReader.readWords(transcript);
        List<Double> sceneCuts = readScenes(scenesFile);
        List<Silence> silences = readSilences(audioFeaturesFile);
        createDirectory(clipDirectory);

        List<Clip> clips = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            clips.add(renderOne(source, words, silences, sceneCuts, scored.get(index), index + 1, clipDirectory));
        }
        log.info("Rendered {} clips into {}", clips.size(), clipDirectory);
        write(clips, target);
    }

    private Clip renderOne(Path source, List<Word> words, List<Silence> silences, List<Double> sceneCuts,
            ScoredCandidate scored, int number, Path clipDirectory) {
        var candidate = scored.candidate();
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(candidate, words, silences, sceneCuts);
        double start = bounds.start();
        double end = bounds.end();
        Path subtitles = clipDirectory.resolve("clip-%d.ass".formatted(number));
        Path video = clipDirectory.resolve("clip-%d.mp4".formatted(number));

        subtitleWriter.write(words, start, end, subtitles);
        ffmpegClient.renderVertical(source, start, end - start, subtitles, video);

        return new Clip(number, start, end, scored.overallScore(), scored.score().reason(),
                video.getFileName().toString());
    }

    private List<Double> readScenes(Path scenesFile) {
        if (!Files.exists(scenesFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(scenesFile), new TypeReference<List<Double>>() {
            });
        } catch (IOException exception) {
            throw new KirinukiException("Could not read scenes " + scenesFile, exception);
        }
    }

    private List<Silence> readSilences(Path audioFeaturesFile) {
        if (!Files.exists(audioFeaturesFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(audioFeaturesFile), AudioFeatures.class).silences();
        } catch (IOException exception) {
            throw new KirinukiException("Could not read audio features " + audioFeaturesFile, exception);
        }
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
