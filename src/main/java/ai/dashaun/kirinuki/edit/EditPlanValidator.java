package ai.dashaun.kirinuki.edit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

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

    private final KirinukiPipelineProperties properties;

    public EditPlanValidator(KirinukiPipelineProperties properties) {
        this.properties = properties;
    }

    public EditPlan validateAndRepair(EditPlan plan, double clipDuration, List<Word> words) {
        if (clipDuration <= 0) {
            throw new InvalidEditPlanException("Clip duration must be positive");
        }

        List<Shot> shots = plan.shots() == null ? List.of() : new ArrayList<>(plan.shots());
        shots = clean(shots, clipDuration);
        shots = fillGaps(shots, clipDuration);
        shots = enforceDurations(shots);
        shots = enforceDesignedRatio(shots, clipDuration);
        shots = validateAndRepairFields(shots);
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
            cleaned.add(new Shot(in, out, block, shot.headline(), shot.subline(), shot.lines(), shot.logos(),
                    shot.imageQuery(), shot.zoom(), shot.transition()));
        }
        cleaned.sort(Comparator.comparingDouble(Shot::in));

        List<Shot> trimmed = new ArrayList<>();
        for (Shot shot : cleaned) {
            if (!trimmed.isEmpty()) {
                Shot last = trimmed.getLast();
                if (shot.in() < last.out()) {
                    shot = new Shot(last.out(), shot.out(), shot.block(), shot.headline(), shot.subline(),
                            shot.lines(), shot.logos(), shot.imageQuery(), shot.zoom(), shot.transition());
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
                        result.add(new Shot(cursor, end, shot.block(), shot.headline(), shot.subline(), shot.lines(),
                                shot.logos(), shot.imageQuery(), shot.zoom(), shot.transition()));
                    } else if (!result.isEmpty()) {
                        Shot last = result.getLast();
                        result.set(result.size() - 1,
                                new Shot(last.in(), end, last.block(), last.headline(), last.subline(), last.lines(),
                                        last.logos(), last.imageQuery(), last.zoom(), last.transition()));
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
            converted.set(i, new Shot(shot.in(), shot.out(), ShotBlock.SOURCE, null, null, null, null, null,
                    shot.zoom(), shot.transition()));
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
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, null, null, null, null, shot.zoom(),
                            shot.transition());
                }
                case HIGHLIGHT -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    String subline = trim(shot.subline(), MAX_SUBLINE_LENGTH);
                    if (headline == null || headline.isBlank() || subline == null || subline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, subline, null, null, null, shot.zoom(),
                            shot.transition());
                }
                case TERMINAL, CODE -> {
                    List<String> lines = trimLines(shot.lines());
                    if (lines.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), null, null, lines, null, null, shot.zoom(),
                            shot.transition());
                }
                case LOGO_WALL -> {
                    List<String> logos = trimList(shot.logos(), MAX_LOGOS);
                    if (logos.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), null, null, null, logos, null, shot.zoom(),
                            shot.transition());
                }
                case DIAGRAM -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    List<String> lines = trimLines(shot.lines());
                    if (headline == null || headline.isBlank() || lines.isEmpty()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, null, lines, null, null, shot.zoom(),
                            shot.transition());
                }
                case STAMP -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    if (headline == null || headline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, null, null, null, null, shot.zoom(),
                            shot.transition());
                }
                case COUNTER -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    String subline = trim(shot.subline(), MAX_SUBLINE_LENGTH);
                    if (headline == null || headline.isBlank() || subline == null || subline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, subline, null, null, null, shot.zoom(),
                            shot.transition());
                }
                case IMAGE -> {
                    String imageQuery = trim(shot.imageQuery(), MAX_HEADLINE_LENGTH);
                    if (imageQuery == null || imageQuery.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), trim(shot.headline(), MAX_HEADLINE_LENGTH), null,
                            null, null, imageQuery, shot.zoom(), shot.transition());
                }
                case OUTRO -> {
                    String headline = trim(shot.headline(), MAX_HEADLINE_LENGTH);
                    if (headline == null || headline.isBlank()) {
                        yield sourceFrom(shot);
                    }
                    yield new Shot(shot.in(), shot.out(), shot.block(), headline, null, null, null, null, shot.zoom(),
                            shot.transition());
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
                result.add(new Shot(shot.in(), shot.out(), shot.block(), shot.headline(), shot.subline(), shot.lines(),
                        filtered, shot.imageQuery(), shot.zoom(), shot.transition()));
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
                result.set(result.size() - 1, new Shot(last.in(), outroIn, last.block(), last.headline(), last.subline(),
                        last.lines(), last.logos(), last.imageQuery(), last.zoom(), last.transition()));
            } else {
                outroIn = last.in();
                result.removeLast();
            }
        }

        result.add(new Shot(outroIn, clipDuration, ShotBlock.OUTRO, "Follow for more", null, null, null, null, null,
                null));
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

    private Shot sourceFrom(Shot shot) {
        return new Shot(shot.in(), shot.out(), ShotBlock.SOURCE, null, null, null, null, null, shot.zoom(),
                shot.transition());
    }

    private Shot sourceShot(double in, double out) {
        return new Shot(in, out, ShotBlock.SOURCE, null, null, null, null, null, null, null);
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
