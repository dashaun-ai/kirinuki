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
import ai.dashaun.kirinuki.media.AudioFeatures;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.MediaProfile;
import ai.dashaun.kirinuki.media.Silence;
import ai.dashaun.kirinuki.media.SpeakerSegment;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.vision.FaceTracks;
import ai.dashaun.kirinuki.vision.RoiPlanner;
import ai.dashaun.kirinuki.vision.ScreenAnalysis;
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
    private final RoiPlanner roiPlanner;
    private final KirinukiPipelineProperties properties;

    public ClipRenderer(ObjectMapper objectMapper, TranscriptReader transcriptReader, SubtitleWriter subtitleWriter,
            FfmpegClient ffmpegClient, BoundaryRefiner boundaryRefiner, RoiPlanner roiPlanner,
            KirinukiPipelineProperties properties) {
        this.objectMapper = objectMapper;
        this.transcriptReader = transcriptReader;
        this.subtitleWriter = subtitleWriter;
        this.ffmpegClient = ffmpegClient;
        this.boundaryRefiner = boundaryRefiner;
        this.roiPlanner = roiPlanner;
        this.properties = properties;
    }

    public void render(Path source, Path transcript, Path scoredFile, Path scenesFile, Path audioFeaturesFile,
            Path facesFile, Path screenFile, Path clipDirectory, Path target) {
        List<ScoredCandidate> scored = readScored(scoredFile);
        List<Word> words = transcriptReader.readWords(transcript);
        List<Double> sceneCuts = readScenes(scenesFile);
        List<Silence> silences = readSilences(audioFeaturesFile);
        FaceTracks faces = readFaces(facesFile);
        ScreenAnalysis screen = readScreen(screenFile);
        MediaProfile profile = ffmpegClient.probeMediaProfile(source);
        createDirectory(clipDirectory);

        List<Clip> clips = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            clips.add(renderOne(source, words, silences, sceneCuts, faces, screen, profile, scored.get(index),
                    index + 1, clipDirectory));
        }
        log.info("Rendered {} clips into {}", clips.size(), clipDirectory);
        write(clips, target);
    }

    private Clip renderOne(Path source, List<Word> words, List<Silence> silences, List<Double> sceneCuts,
            FaceTracks faces, ScreenAnalysis screen, MediaProfile profile, ScoredCandidate scored, int number,
            Path clipDirectory) {
        var candidate = scored.candidate();
        BoundaryRefiner.Bounds bounds = boundaryRefiner.refine(candidate, words, silences, sceneCuts);
        double start = bounds.start();
        double end = bounds.end();
        Path subtitles = clipDirectory.resolve("clip-%d.ass".formatted(number));
        Path video = clipDirectory.resolve("clip-%d.mp4".formatted(number));

        if (Files.exists(video)) {
            log.info("Clip {} already rendered at {}, skipping", number, video);
            return new Clip(number, start, end, scored.overallScore(), scored.score().reason(),
                    video.getFileName().toString());
        }

        subtitleWriter.write(words, start, end, subtitles);
        FramingPlan plan = framingPlan(screen, faces, words, start, end, profile);
        Path part = clipDirectory.resolve("clip-%d.part.mp4".formatted(number));
        ffmpegClient.renderVertical(source, start, end - start, subtitles, part, plan, profile);
        commit(part, video);

        return new Clip(number, start, end, scored.overallScore(), scored.score().reason(),
                video.getFileName().toString());
    }

    private void commit(Path part, Path target) {
        try {
            Files.move(part, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new KirinukiException("Could not commit rendered clip to " + target, exception);
        }
    }

    private FramingPlan framingPlan(ScreenAnalysis screen, FaceTracks faces, List<Word> words, double start,
            double end, MediaProfile profile) {
        String framing = properties.render().framing();
        if ("blur-fill".equals(framing)) {
            return null;
        }
        return roiPlanner.plan(screen, faces, words, start, end, profile, properties).orElse(null);
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

    private FaceTracks readFaces(Path facesFile) {
        if (!Files.exists(facesFile)) {
            return FaceTracks.empty();
        }
        try {
            return objectMapper.readValue(Files.readString(facesFile), FaceTracks.class);
        } catch (IOException exception) {
            log.warn("Could not read faces {}, continuing without reframing", facesFile, exception);
            return FaceTracks.empty();
        }
    }

    private ScreenAnalysis readScreen(Path screenFile) {
        if (!Files.exists(screenFile)) {
            return ScreenAnalysis.empty();
        }
        try {
            return objectMapper.readValue(Files.readString(screenFile), ScreenAnalysis.class);
        } catch (IOException exception) {
            log.warn("Could not read screen analysis {}, continuing without ROI framing", screenFile, exception);
            return ScreenAnalysis.empty();
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
