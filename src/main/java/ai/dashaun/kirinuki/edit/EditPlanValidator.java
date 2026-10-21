package ai.dashaun.kirinuki.edit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties.Edit.Renderer;
import ai.dashaun.kirinuki.vision.Scene;

@Component
public class EditPlanValidator {

    private static final Set<String> BUNDLED_LOGOS = Set.of(
            "spring", "openjdk", "graalvm", "testcontainers", "docker", "postgresql", "ollama");

    private static final int MAX_HEADLINE_LENGTH = 80;
    private static final int MAX_SUBLINE_LENGTH = 120;
    private static final int MAX_LINE_LENGTH = 120;
    private static final int MAX_LINES = 10;
    private static final int MAX_LOGOS = 12;
    private static final int SLAM_MAX_WORDS = 3;
    private static final double SPEAKER_BALANCE_THRESHOLD = 0.7;

    private final KirinukiPipelineProperties properties;

    public EditPlanValidator(KirinukiPipelineProperties properties) {
        this.properties = properties;
    }

    public EditPlan validateAndRepair(EditPlan plan, double clipDuration, Scene scene, List<Word> words) {
        if (clipDuration <= 0) {
            throw new InvalidEditPlanException("Clip duration must be positive");
        }

        List<Shot> shots = plan.shots() == null ? List.of() : new ArrayList<>(plan.shots());
        shots = clean(shots, clipDuration);
        shots = fillGaps(shots, clipDuration);
        shots = enforceDurations(shots);
        shots = enforceDesignedRatio(shots, clipDuration);
        shots = validateAndRepairFields(shots);
        shots = enforceShotTypes(shots, scene);
        shots = enforceRendererAndMultiSpeaker(shots);
        shots = enforceSpeakerBalance(shots, scene);
        shots = filterLogos(shots);

        if (shots.isEmpty()) {
            throw new InvalidEditPlanException("Plan has no valid shots");
        }

        shots = forceOutro(shots, clipDuration);

        List<Emphasis> emphasis = snapEmphasis(plan.emphasis(), words);
        return new EditPlan(plan.clipIndex(), plan.theme(), shots, emphasis);
    }

    private List<Shot> clean(List<Shot> shots, double clipDuration) {
        List<Shot> cleaned = new ArrayList<>();
        for (Shot shot : shots) {
            double in = clamp(shot.in(), 0, clipDuration);
            double out = clamp(shot.out(), 0, clipDuration);
            if (out <= in) {
                continue;
            }
            ShotBlock block = shot.block() == null ? ShotBlock.SOURCE : shot.block();
            cleaned.add(copy(shot, in, out, block));
        }
        cleaned.sort(Comparator.comparingDouble(Shot::in));

        List<Shot> trimmed = new ArrayList<>();
        for (Shot shot : cleaned) {
            if (!trimmed.isEmpty()) {
                Shot last = trimmed.getLast();
                if (shot.in() < last.out()) {
                    shot = copy(shot, last.out(), shot.out());
                    if (shot.out() <= shot.in()) {
                        continue;
                    }
                }
            }
            trimmed.add(shot);
        }
        return trimmed;
    }

    private List<Shot> fillGaps(List<Shot> shots, double clipDuration) {
        List<Shot> filled = new ArrayList<>();
        double cursor = 0;
        for (Shot shot : shots) {
            if (shot.in() > cursor) {
                filled.add(sourceShot(cursor, shot.in()));
            }
            filled.add(shot);
            cursor = Math.max(cursor, shot.out());
        }
        if (cursor < clipDuration) {
            filled.add(sourceShot(cursor, clipDuration));
        }
        return filled;
    }

    private List<Shot> enforceDurations(List<Shot> shots) {
        double min = properties.edit().minShotSeconds();
        double max = properties.edit().maxShotSeconds();
        double maxSource = properties.edit().maxSourceSeconds();

        List<Shot> result = new ArrayList<>();
        for (Shot shot : shots) {
            double duration = shot.out() - shot.in();
            if (duration < min) {
                continue;
            }
            double limit = shot.block() == ShotBlock.SOURCE ? maxSource : max;
            if (duration > limit && limit > 0) {
                double cursor = shot.in();
                while (cursor < shot.out()) {
                    double end = Math.min(cursor + limit, shot.out());
                    if (end - cursor >= min) {
                        result.add(copy(shot, cursor, end));
                    } else if (!result.isEmpty()) {
                        Shot last = result.getLast();
                        result.set(result.size() - 1, copy(last, last.in(), end));
                    }
                    cursor = end;
                }
            } else {
                result.add(shot);
            }
        }
        return result;
    }

    private List<Shot> enforceDesignedRatio(List<Shot> shots, double clipDuration) {
        double maxRatio = properties.edit().maxDesignedRatio();
        double designed = shots.stream()
                .filter(shot -> shot.block() != ShotBlock.SOURCE)
                .mapToDouble(shot -> shot.out() - shot.in())
                .sum();
        if (designed / clipDuration <= maxRatio) {
            return shots;
        }

        List<Shot> converted = new ArrayList<>(shots);
        double budget = clipDuration * maxRatio;
        double current = 0;
        for (Shot shot : converted) {
            if (shot.block() != ShotBlock.SOURCE) {
                current += shot.out() - shot.in();
            }
        }

        for (int i = converted.size() - 2; i >= 0 && current > budget; i--) {
            Shot shot = converted.get(i);
            if (shot.block() == ShotBlock.SOURCE) {
                continue;
            }
            converted.set(i, sourceFrom(shot));
            current -= shot.out() - shot.in();
        }
        return converted;
    }

    private List<Shot> validateAndRepairFields(List<Shot> shots) {
        List<Shot> repaired = new ArrayList<>();
        for (Shot shot : shots) {
            Shot fixed = switch (shot.block()) {
                case SOURCE -> shot;
                case SLAM -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    if (headline == null || headline.isBlank() || headline.split("\\s+").length > SLAM_MAX_WORDS) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, null, null);
                }
                case HIGHLIGHT -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    String subline = trim(shot.subline(), MAX_SUBLINE_LENGTH);
                    if (headline == null || headline.isBlank() || subline == null || subline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, subline, null);
                }
                case TERMINAL, CODE -> {
                    List<String> lines = trimLines(shot.lines());
                    if (lines.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, null, null, lines);
                }
                case LOGO_WALL -> {
                    List<String> logos = trimList(shot.logos(), MAX_LOGOS);
                    if (logos.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield withLogos(shot, logos);
                }
                case DIAGRAM -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    List<String> lines = trimLines(shot.lines());
                    if (headline == null || headline.isBlank() || lines.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, null, lines);
                }
                case STAMP -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    if (headline == null || headline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, null, null);
                }
                case COUNTER -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    String subline = trim(shot.subline(), MAX_SUBLINE_LENGTH);
                    if (headline == null || headline.isBlank() || subline == null || subline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, subline, null);
                }
                case IMAGE -> {
                    String imageQuery = trim(shot.imageQuery(), MAX_HEADLINE_LENGTH);
                    if (imageQuery == null || imageQuery.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield withImage(shot, trim(shot.headline(), MAX_HEADLINE_LENGTH), imageQuery);
                }
                case OUTRO -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    if (headline == null || headline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield withText(shot, headline, null, null);
                }
            };
            repaired.add(fixed);
        }
        return repaired;
    }

    private List<Shot> filterLogos(List<Shot> shots) {
        List<Shot> result = new ArrayList<>();
        for (Shot shot : shots) {
            if (shot.block() == ShotBlock.LOGO_WALL && shot.logos() != null) {
                List<String> filtered = shot.logos().stream()
                        .filter(BUNDLED_LOGOS::contains)
                        .distinct()
                        .toList();
                if (filtered.isEmpty()) {
                    result.add(sourceFrom(shot));
                    continue;
                }
                result.add(withLogos(shot, filtered));
            } else {
                result.add(shot);
            }
        }
        return result;
    }

    private List<Shot> forceOutro(List<Shot> shots, double clipDuration) {
        if (shots.isEmpty()) {
            throw new InvalidEditPlanException("Cannot force outro on empty plan");
        }
        if (shots.getLast().block() == ShotBlock.OUTRO) {
            return shots;
        }

        double min = properties.edit().minShotSeconds();
        double outroDuration = Math.min(Math.max(2.5, min), clipDuration);
        double outroIn = clipDuration - outroDuration;

        List<Shot> result = new ArrayList<>(shots);
        while (!result.isEmpty() && result.getLast().in() >= outroIn - 0.001) {
            outroIn = result.getLast().in();
            result.removeLast();
        }

        if (!result.isEmpty()) {
            Shot last = result.getLast();
            if (outroIn - last.in() >= min) {
                result.set(result.size() - 1, copy(last, last.in(), outroIn));
            } else {
                outroIn = last.in();
                result.removeLast();
            }
        }

        result.add(new Shot(outroIn, clipDuration, ShotBlock.OUTRO, "Follow for more", null, null, null, null, null,
                null, null, null, null, null, null));
        return result;
    }

    private List<Emphasis> snapEmphasis(List<Emphasis> emphasis, List<Word> words) {
        if (emphasis == null || emphasis.isEmpty() || words == null || words.isEmpty()) {
            return List.of();
        }
        List<Emphasis> snapped = new ArrayList<>();
        for (Emphasis e : emphasis) {
            Word nearest = null;
            double best = Double.MAX_VALUE;
            for (Word word : words) {
                double distance = Math.abs(word.start() - e.at());
                if (distance < best) {
                    best = distance;
                    nearest = word;
                }
            }
            if (nearest != null) {
                snapped.add(new Emphasis(e.word(), nearest.start(), e.kind() == null ? EmphasisKind.PUNCH : e.kind()));
            }
        }
        return snapped;
    }

    private Shot copy(Shot shot, double in, double out) {
        return copy(shot, in, out, shot.block());
    }

    private Shot copy(Shot shot, ShotBlock block) {
        return copy(shot, shot.in(), shot.out(), block);
    }

    private Shot copy(Shot shot, double in, double out, ShotBlock block) {
        return new Shot(in, out, block, shot.headline(), shot.subline(), shot.lines(), shot.logos(), shot.imageQuery(),
                shot.zoom(), shot.transition(), shot.type(), shot.speaker(), shot.speakers(), shot.kinetic(),
                shot.focus());
    }

    private Shot withText(Shot shot, String headline, String subline, List<String> lines) {
        return new Shot(shot.in(), shot.out(), shot.block(), headline, subline, lines, shot.logos(), shot.imageQuery(),
                shot.zoom(), shot.transition(), shot.type(), shot.speaker(), shot.speakers(), shot.kinetic(),
                shot.focus());
    }

    private Shot withLogos(Shot shot, List<String> logos) {
        return new Shot(shot.in(), shot.out(), shot.block(), shot.headline(), shot.subline(), shot.lines(), logos,
                shot.imageQuery(), shot.zoom(), shot.transition(), shot.type(), shot.speaker(), shot.speakers(),
                shot.kinetic(), shot.focus());
    }

    private Shot withImage(Shot shot, String headline, String imageQuery) {
        return new Shot(shot.in(), shot.out(), shot.block(), headline, shot.subline(), shot.lines(), shot.logos(),
                imageQuery, shot.zoom(), shot.transition(), shot.type(), shot.speaker(), shot.speakers(),
                shot.kinetic(), shot.focus());
    }

    private List<Shot> enforceShotTypes(List<Shot> shots, Scene scene) {
        boolean hasCode = scene != null && scene.hasCode();
        List<Shot> result = new ArrayList<>();
        for (Shot shot : shots) {
            ShotType type = shot.type();
            if ((type == ShotType.CODE || type == ShotType.MIXED) && !hasCode) {
                result.add(copy(shot, shot.in(), shot.out(), ShotBlock.SOURCE)
                        .withType(ShotType.SPEAKER)
                        .withFocus(null));
            } else if (type == ShotType.KINETIC && shot.kinetic() == null) {
                result.add(copy(shot, shot.in(), shot.out(), ShotBlock.SOURCE)
                        .withType(ShotType.SPEAKER)
                        .withKinetic(null));
            } else {
                result.add(shot);
            }
        }
        return result;
    }

    private List<Shot> enforceRendererAndMultiSpeaker(List<Shot> shots) {
        Renderer renderer = properties.edit().renderer();
        boolean multiSpeaker = properties.edit().multiSpeaker();
        List<Shot> result = new ArrayList<>();
        for (Shot shot : shots) {
            ShotType type = shot.type();
            if (renderer == Renderer.FFMPEG && type == ShotType.KINETIC) {
                result.add(sourceFrom(shot).withType(ShotType.SPEAKER).withKinetic(null).withFocus(null));
                continue;
            }
            if (renderer == Renderer.KINETIC
                    && (type == ShotType.CODE || type == ShotType.DIALOGUE_2UP || type == ShotType.DIALOGUE_3UP
                            || type == ShotType.MIXED)) {
                result.add(sourceFrom(shot).withType(ShotType.SPEAKER).withKinetic(null).withFocus(null));
                continue;
            }
            if (!multiSpeaker && (type == ShotType.DIALOGUE_2UP || type == ShotType.DIALOGUE_3UP)) {
                result.add(sourceFrom(shot).withType(ShotType.SPEAKER).withFocus(null));
                continue;
            }
            result.add(shot);
        }
        return result;
    }

    private List<Shot> enforceSpeakerBalance(List<Shot> shots, Scene scene) {
        if (scene == null || scene.speakers().size() < 2) {
            return shots;
        }
        double speakerDuration = shots.stream()
                .filter(shot -> shot.block() != ShotBlock.OUTRO && shot.speaker() != null)
                .mapToDouble(shot -> shot.out() - shot.in())
                .sum();
        if (speakerDuration <= 0) {
            return shots;
        }
        List<Shot> result = new ArrayList<>(shots);
        for (String speakerId : scene.speakers().stream().map(ai.dashaun.kirinuki.vision.SceneSpeaker::id).toList()) {
            double current = result.stream()
                    .filter(shot -> shot.block() != ShotBlock.OUTRO && speakerId.equals(shot.speaker()))
                    .mapToDouble(shot -> shot.out() - shot.in())
                    .sum();
            if (current / speakerDuration <= SPEAKER_BALANCE_THRESHOLD) {
                continue;
            }
            double target = speakerDuration * SPEAKER_BALANCE_THRESHOLD;
            for (int i = result.size() - 2; i >= 0 && current > target; i--) {
                Shot shot = result.get(i);
                if (shot.block() == ShotBlock.OUTRO || !speakerId.equals(shot.speaker())) {
                    continue;
                }
                result.set(i, copy(shot, shot.in(), shot.out(), ShotBlock.SOURCE)
                        .withType(ShotType.SPEAKER)
                        .withSpeaker(null));
                current -= shot.out() - shot.in();
            }
        }
        return result;
    }

    private Shot sourceFrom(Shot shot) {
        return new Shot(shot.in(), shot.out(), ShotBlock.SOURCE, null, null, null, null, null, shot.zoom(),
                shot.transition(), shot.type(), shot.speaker(), shot.speakers(), shot.kinetic(), shot.focus());
    }

    private Shot sourceShot(double in, double out) {
        return new Shot(in, out, ShotBlock.SOURCE, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() : trimmed;
    }

    private List<String> trimLines(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(line -> trim(line, MAX_LINE_LENGTH))
                .filter(line -> line != null && !line.isBlank())
                .limit(MAX_LINES)
                .toList();
    }

    private List<String> trimList(List<String> values, int maxSize) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(maxSize)
                .toList();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
