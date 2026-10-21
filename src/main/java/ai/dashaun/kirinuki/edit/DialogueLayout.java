package ai.dashaun.kirinuki.edit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import ai.dashaun.kirinuki.render.SubtitleWriter;
import ai.dashaun.kirinuki.vision.FaceSample;
import ai.dashaun.kirinuki.vision.FaceTrack;
import ai.dashaun.kirinuki.vision.Scene;
import ai.dashaun.kirinuki.vision.SceneSpeaker;

@Component
class DialogueLayout implements LayoutRenderer {

    private static final int OVERLAY_MARGIN = 40;
    private static final double FACE_MARGIN = 2.8;

    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;
    private final SubtitleWriter subtitleWriter;

    DialogueLayout(KirinukiPipelineProperties properties, ProcessRunner processRunner, SubtitleWriter subtitleWriter) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.subtitleWriter = subtitleWriter;
    }

    @Override
    public boolean supports(ShotType type) {
        return type == ShotType.DIALOGUE_2UP || type == ShotType.DIALOGUE_3UP;
    }

    @Override
    public void render(Path source, List<Word> words, double clipStart, double clipDuration, Shot shot, Scene scene,
            Path output) {
        double shotStart = clipStart + Math.max(0, shot.in());
        double shotEnd = clipStart + Math.min(shot.out(), clipDuration);
        double duration = Math.max(0.1, shotEnd - shotStart);

        Path subtitles = output.resolveSibling(output.getFileName() + ".ass");
        subtitleWriter.write(words, shotStart, shotEnd, subtitles);

        KirinukiPipelineProperties.Render render = properties.render();
        int width = render.width();
        int height = render.height();
        double zoom = shot.zoom() != null && shot.zoom() > 1 ? shot.zoom() : 1.0;

        List<String> speakers = orderedSpeakers(shot, scene);
        List<String> active = scene == null ? List.of() : scene.activeSpeakers(shotStart, shotEnd);

        String filter = switch (shot.type()) {
            case DIALOGUE_2UP -> twoUpFilter(scene, speakers, active, width, height, zoom, shotStart, shotEnd,
                    subtitles);
            case DIALOGUE_3UP -> threeUpFilter(scene, speakers, active, width, height, zoom, shotStart, shotEnd,
                    shot, subtitles);
            default -> fullFrameFilter(width, height, zoom, subtitles, shot);
        };

        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-ss", String.valueOf(shotStart),
                "-t", String.valueOf(duration),
                "-i", source.toString(),
                "-filter_complex", filter,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", String.valueOf(render.crf()),
                "-c:a", "aac",
                "-af", "loudnorm=I=-14:TP=-1.5:LRA=11",
                "-b:a", "128k",
                "-ar", "48000",
                "-r", "30",
                "-pix_fmt", "yuv420p",
                output.toString()), properties.media().timeout());
    }

    private List<String> orderedSpeakers(Shot shot, Scene scene) {
        List<String> ordered = new ArrayList<>();
        if (shot.speakers() != null) {
            ordered.addAll(shot.speakers());
        }
        if (scene != null && scene.speakers() != null) {
            for (SceneSpeaker speaker : scene.speakers()) {
                if (!ordered.contains(speaker.id())) {
                    ordered.add(speaker.id());
                }
            }
        }
        return ordered;
    }

    private String twoUpFilter(Scene scene, List<String> speakers, List<String> active, int width, int height,
            double zoom, double shotStart, double shotEnd, Path subtitles) {
        if (active.size() <= 1 || speakers.size() < 2) {
            return fullFrameFilter(width, height, zoom, subtitles, null);
        }
        String leftSpeaker = active.get(0);
        String rightSpeaker = active.size() > 1 ? active.get(1) : speakers.get(1);
        int panelW = width / 2;
        int panelH = height;

        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]split=2[base1][base2];");
        filter.append(panelFilter("base1", "left", leftSpeaker, scene, panelW, panelH, zoom, shotStart, shotEnd,
                PanelRole.LEFT));
        filter.append(panelFilter("base2", "right", rightSpeaker, scene, panelW, panelH, zoom, shotStart, shotEnd,
                PanelRole.RIGHT));
        filter.append("[left][right]hstack=inputs=2[stacked];");
        filter.append("[stacked]subtitles=%s,format=yuv420p".formatted(FfmpegClient.escapeFilterPath(subtitles)));
        return filter.toString();
    }

    private String threeUpFilter(Scene scene, List<String> speakers, List<String> active, int width, int height,
            double zoom, double shotStart, double shotEnd, Shot shot, Path subtitles) {
        if (active.isEmpty()) {
            return fullFrameFilter(width, height, zoom, subtitles, shot);
        }
        String mainSpeaker = scene.activeSpeaker(shotStart + (shotEnd - shotStart) / 2.0)
                .filter(active::contains)
                .orElse(active.get(0));
        List<String> others = active.stream()
                .filter(speaker -> !speaker.equals(mainSpeaker))
                .limit(2)
                .toList();
        if (others.isEmpty()) {
            return fullFrameFilter(width, height, zoom, subtitles, shot);
        }

        int thumbW = (width - 3 * OVERLAY_MARGIN) / 2;
        int thumbH = thumbW;
        int mainH = height - thumbH - 2 * OVERLAY_MARGIN;

        StringBuilder filter = new StringBuilder();
        int splits = Math.min(3, 1 + others.size());
        filter.append("[0:v]split=%d".formatted(splits));
        for (int index = 0; index < splits; index++) {
            filter.append("[base%d]".formatted(index));
        }
        filter.append(";");
        filter.append(panelFilter("base0", "main", mainSpeaker, scene, width, mainH, zoom, shotStart, shotEnd,
                PanelRole.CENTER));
        for (int index = 0; index < others.size(); index++) {
            filter.append(panelFilter("base%d".formatted(index + 1), "thumb%d".formatted(index), others.get(index),
                    scene, thumbW, thumbH, zoom, shotStart, shotEnd, PanelRole.THUMB));
        }

        filter.append("color=black:s=%dx%d:d=%.3f[canvas];".formatted(width, height, shotEnd - shotStart));
        filter.append("[canvas][main]overlay=(W-w)/2:0[tmp0];");
        for (int index = 0; index < others.size(); index++) {
            int x = others.size() == 1 ? (width - thumbW) / 2
                    : (index == 0 ? OVERLAY_MARGIN : width - thumbW - OVERLAY_MARGIN);
            int y = height - thumbH - OVERLAY_MARGIN;
            filter.append("[tmp%d][thumb%d]overlay=%d:%d".formatted(index, index, x, y));
            if (index < others.size() - 1) {
                filter.append("[tmp%d];".formatted(index + 1));
            }
        }
        filter.append(",subtitles=%s,format=yuv420p".formatted(FfmpegClient.escapeFilterPath(subtitles)));
        return filter.toString();
    }

    private String panelFilter(String input, String output, String speakerId, Scene scene, int targetW, int targetH,
            double zoom, double shotStart, double shotEnd, PanelRole role) {
        Crop crop = cropFor(speakerId, scene, targetW, targetH, zoom, shotStart, shotEnd, role);
        return "[%s]crop=%d:%d:%d:%d,scale=%d:%d:force_original_aspect_ratio=decrease,setsar=1,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,scale=%d:%d[%s];"
                .formatted(input, crop.w(), crop.h(), crop.x(), crop.y(), targetW * 2, targetH * 2, targetW * 2,
                        targetH * 2, targetW, targetH, output);
    }

    private Crop cropFor(String speakerId, Scene scene, int targetW, int targetH, double zoom, double shotStart,
            double shotEnd, PanelRole role) {
        if (scene == null) {
            return defaultCrop(targetW, targetH, role, 1920, 1080);
        }
        Optional<FaceTrack> track = scene.faceTrack(speakerId);
        if (track.isEmpty()) {
            return defaultCrop(targetW, targetH, role, scene.faceTracks().frameWidth(), scene.faceTracks().frameHeight());
        }
        List<FaceSample> samples = track.get().samples().stream()
                .filter(sample -> sample.t() >= shotStart && sample.t() <= shotEnd)
                .toList();
        if (samples.isEmpty()) {
            return defaultCrop(targetW, targetH, role, scene.faceTracks().frameWidth(), scene.faceTracks().frameHeight());
        }

        double avgCx = samples.stream().mapToDouble(FaceSample::cx).average().orElse(0);
        double avgCy = samples.stream().mapToDouble(FaceSample::cy).average().orElse(0);
        double avgW = samples.stream().mapToDouble(FaceSample::width).average().orElse(0);
        double avgH = samples.stream().mapToDouble(FaceSample::height).average().orElse(0);

        int frameW = scene.faceTracks().frameWidth();
        int frameH = scene.faceTracks().frameHeight();
        double aspect = targetW / (double) targetH;
        double cropW = avgW * zoom * FACE_MARGIN;
        double cropH = cropW / aspect;
        if (cropH < avgH * zoom * FACE_MARGIN) {
            cropH = avgH * zoom * FACE_MARGIN;
            cropW = cropH * aspect;
        }
        if (cropW > frameW) {
            cropW = frameW;
            cropH = cropW / aspect;
        }
        if (cropH > frameH) {
            cropH = frameH;
            cropW = cropH * aspect;
        }

        int w = roundEven(cropW);
        int h = roundEven(cropH);
        int x = roundEven(clamp(avgCx - w / 2.0, 0, frameW - w));
        int y = roundEven(clamp(avgCy - h * 0.4, 0, frameH - h));
        if (x + w > frameW) {
            x = frameW - w;
        }
        if (y + h > frameH) {
            y = frameH - h;
        }
        return new Crop(x, y, w, h);
    }

    private Crop defaultCrop(int targetW, int targetH, PanelRole role, int frameW, int frameH) {
        if (frameW <= 0 || frameH <= 0) {
            frameW = 1920;
            frameH = 1080;
        }
        double aspect = targetW / (double) targetH;
        return switch (role) {
            case LEFT -> new Crop(0, 0, roundEven(frameH * aspect), frameH);
            case RIGHT -> new Crop(frameW - roundEven(frameH * aspect), 0, roundEven(frameH * aspect), frameH);
            case THUMB -> {
                int size = roundEven(Math.min(frameW, frameH));
                yield new Crop(0, frameH - size, size, size);
            }
            case CENTER -> {
                int h = frameH;
                int w = roundEven(h * aspect);
                yield new Crop((frameW - w) / 2, 0, w, h);
            }
        };
    }

    private String fullFrameFilter(int width, int height, double zoom, Path subtitles, Shot shot) {
        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]scale=w=%d:h=%d:force_original_aspect_ratio=decrease,setsar=1"
                .formatted(width * 2, height * 2));
        if (zoom > 1.0001) {
            int cropW = (int) Math.round(width * 2 / zoom) / 2 * 2;
            int cropH = (int) Math.round(height * 2 / zoom) / 2 * 2;
            filter.append(",crop=%d:%d:(iw-%d)/2:(ih-%d)/2,scale=%d:%d"
                    .formatted(cropW, cropH, cropW, cropH, width * 2, height * 2));
        }
        filter.append(",pad=%d:%d:(ow-iw)/2:(oh-ih)/2".formatted(width * 2, height * 2));
        filter.append(",scale=%d:%d".formatted(width, height));
        filter.append(",subtitles=%s".formatted(FfmpegClient.escapeFilterPath(subtitles)));
        if (shot != null && shot.headline() != null && !shot.headline().isBlank()) {
            String boldFont = fontfile(properties.edit().boldFontPath());
            if (boldFont != null) {
                filter.append(
                        ",drawtext=fontfile='%s':text='%s':x=(w-text_w)/2:y=140:fontsize=72:fontcolor=white:borderw=4:bordercolor=black"
                                .formatted(FfmpegClient.escapeFilterPath(Path.of(boldFont)),
                                        escapeDrawtext(shot.headline())));
            }
        }
        filter.append(",format=yuv420p");
        return filter.toString();
    }

    private String fontfile(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (path.toFile().exists()) {
                return path.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private String escapeDrawtext(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("%", "\\%")
                .replace("\n", " ");
    }

    private int roundEven(double value) {
        return ((int) Math.round(value) / 2) * 2;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum PanelRole {
        LEFT, RIGHT, CENTER, THUMB
    }

    private record Crop(int x, int y, int w, int h) {
    }
}
