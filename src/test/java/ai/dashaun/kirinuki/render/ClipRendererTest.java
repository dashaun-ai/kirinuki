package ai.dashaun.kirinuki.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.TranscriptReader;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.MediaProfile;
import ai.dashaun.kirinuki.media.SpeakerSegment;
import ai.dashaun.kirinuki.scoring.CandidateScore;
import ai.dashaun.kirinuki.scoring.ScoredCandidate;
import ai.dashaun.kirinuki.vision.FaceTracks;
import ai.dashaun.kirinuki.vision.RoiPlanner;
import ai.dashaun.kirinuki.vision.ScreenAnalysis;
import ai.dashaun.kirinuki.vision.ScreenRegion;
import tools.jackson.databind.ObjectMapper;

class ClipRendererTest {

    private static final MediaProfile PROFILE = new MediaProfile(1920, 1080, 30.0, 120.0, true);

    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TranscriptReader transcriptReader = mock(TranscriptReader.class);
    private final SubtitleWriter subtitleWriter = mock(SubtitleWriter.class);
    private final FfmpegClient ffmpegClient = mock(FfmpegClient.class);
    private final BoundaryRefiner boundaryRefiner = mock(BoundaryRefiner.class);
    private final RoiPlanner roiPlanner = mock(RoiPlanner.class);

    @Test
    void should_pass_screen_analysis_to_roi_planner_for_framing() throws Exception {
        KirinukiPipelineProperties properties = properties("content-aware");
        ClipRenderer renderer = new ClipRenderer(objectMapper, transcriptReader, subtitleWriter, ffmpegClient,
                boundaryRefiner, roiPlanner, properties);

        Path source = directory.resolve("source.mp4");
        Path transcript = directory.resolve("transcript.json");
        Path scored = directory.resolve("scored.json");
        Path scenes = directory.resolve("scenes.json");
        Path audioFeatures = directory.resolve("audio-features.json");
        Path faces = directory.resolve("faces.json");
        Path screen = directory.resolve("screen-analysis.json");
        Path clipDirectory = directory.resolve("clips");
        Path target = directory.resolve("clips.json");

        Files.createFile(source);
        Files.writeString(transcript, "{\"segments\":[]}");
        Files.writeString(scored, objectMapper.writeValueAsString(List.of(
                new ScoredCandidate(new Candidate(0, 0.0, 10.0, 0, 0, "test"),
                        new CandidateScore(1, 1, 1, 1, List.of(), "reason"), 10))));
        Files.writeString(scenes, "[]");
        Files.writeString(audioFeatures, "{\"silences\":[]}");
        Files.writeString(faces, objectMapper.writeValueAsString(FaceTracks.empty()));
        Files.writeString(screen, objectMapper.writeValueAsString(
                new ScreenAnalysis(new ScreenRegion(0.25, 0.2, 0.5, 0.5), List.of(), true)));

        when(transcriptReader.readWords(transcript)).thenReturn(List.of());
        when(boundaryRefiner.refine(any(), any(), any(), any())).thenReturn(new BoundaryRefiner.Bounds(0.0, 10.0));
        when(ffmpegClient.probeMediaProfile(source)).thenReturn(PROFILE);
        FramingPlan expectedPlan = FramingPlan.single(864, 1080, new SpeakerSegment(0.0, 10.0, 528, 0));
        when(roiPlanner.plan(any(), any(), any(), anyDouble(), anyDouble(), eq(PROFILE), eq(properties)))
                .thenReturn(Optional.of(expectedPlan));
        doAnswer(invocation -> {
            Files.createFile(invocation.getArgument(4));
            return null;
        }).when(ffmpegClient).renderVertical(eq(source), anyDouble(), anyDouble(), any(), any(), any(), eq(PROFILE));

        renderer.render(source, transcript, scored, scenes, audioFeatures, faces, screen, clipDirectory, target);

        ArgumentCaptor<ScreenAnalysis> screenCaptor = ArgumentCaptor.forClass(ScreenAnalysis.class);
        verify(roiPlanner).plan(screenCaptor.capture(), any(), any(), anyDouble(), anyDouble(), eq(PROFILE),
                eq(properties));
        assertThat(screenCaptor.getValue().hasCode()).isTrue();
        assertThat(screenCaptor.getValue().screenRegion()).isEqualTo(new ScreenRegion(0.25, 0.2, 0.5, 0.5));
        verify(ffmpegClient).renderVertical(eq(source), eq(0.0), anyDouble(), any(), any(), eq(expectedPlan),
                eq(PROFILE));
        assertThat(target).exists();
    }

    @Test
    void should_skip_roi_planner_when_framing_is_blur_fill() throws Exception {
        KirinukiPipelineProperties properties = properties("blur-fill");
        ClipRenderer renderer = new ClipRenderer(objectMapper, transcriptReader, subtitleWriter, ffmpegClient,
                boundaryRefiner, roiPlanner, properties);

        Path source = directory.resolve("source.mp4");
        Path transcript = directory.resolve("transcript.json");
        Path scored = directory.resolve("scored.json");
        Path scenes = directory.resolve("scenes.json");
        Path audioFeatures = directory.resolve("audio-features.json");
        Path faces = directory.resolve("faces.json");
        Path screen = directory.resolve("screen-analysis.json");
        Path clipDirectory = directory.resolve("clips");
        Path target = directory.resolve("clips.json");

        Files.createFile(source);
        Files.writeString(transcript, "{\"segments\":[]}");
        Files.writeString(scored, objectMapper.writeValueAsString(List.of(
                new ScoredCandidate(new Candidate(0, 0.0, 10.0, 0, 0, "test"),
                        new CandidateScore(1, 1, 1, 1, List.of(), "reason"), 10))));
        Files.writeString(scenes, "[]");
        Files.writeString(audioFeatures, "{\"silences\":[]}");
        Files.writeString(faces, objectMapper.writeValueAsString(FaceTracks.empty()));
        Files.writeString(screen, objectMapper.writeValueAsString(
                new ScreenAnalysis(new ScreenRegion(0.25, 0.2, 0.5, 0.5), List.of(), true)));

        when(transcriptReader.readWords(transcript)).thenReturn(List.of());
        when(boundaryRefiner.refine(any(), any(), any(), any())).thenReturn(new BoundaryRefiner.Bounds(0.0, 10.0));
        when(ffmpegClient.probeMediaProfile(source)).thenReturn(PROFILE);
        doAnswer(invocation -> {
            Files.createFile(invocation.getArgument(4));
            return null;
        }).when(ffmpegClient).renderVertical(eq(source), anyDouble(), anyDouble(), any(), any(), any(), eq(PROFILE));

        renderer.render(source, transcript, scored, scenes, audioFeatures, faces, screen, clipDirectory, target);

        verify(roiPlanner, never()).plan(any(), any(), any(), anyDouble(), anyDouble(), any(), any());
        verify(ffmpegClient).renderVertical(eq(source), eq(0.0), anyDouble(), any(), any(), eq((FramingPlan) null),
                eq(PROFILE));
    }

    private KirinukiPipelineProperties properties(String framing) {
        return new Binder(new MapConfigurationPropertySource(Map.of("kirinuki.pipeline.render.framing", framing)))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class);
    }
}
