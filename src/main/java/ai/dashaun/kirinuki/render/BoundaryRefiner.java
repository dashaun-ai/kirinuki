package ai.dashaun.kirinuki.render;

import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Candidate;
import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.Silence;

@Component
public class BoundaryRefiner {

    private final KirinukiPipelineProperties properties;

    public BoundaryRefiner(KirinukiPipelineProperties properties) {
        this.properties = properties;
    }

    public Bounds refine(Candidate candidate, List<Word> words, List<Silence> silences, List<Double> sceneCuts) {
        KirinukiPipelineProperties.Render render = properties.render();
        double leadIn = render.leadIn().toMillis() / 1000.0;
        double tail = render.tail().toMillis() / 1000.0;
        double tolerance = render.snapTolerance().toMillis() / 1000.0;
        double start = refineStart(candidate, words, silences, sceneCuts, leadIn, tolerance);
        double end = refineEnd(candidate, words, silences, sceneCuts, tail, tolerance);
        return new Bounds(start, end);
    }

    private double refineStart(Candidate candidate, List<Word> words, List<Silence> silences, List<Double> sceneCuts,
            double leadIn, double tolerance) {
        Silence pause = nearestSilenceEndingNear(silences, candidate.start(), tolerance);
        if (pause != null) {
            return Math.max(pause.start(), candidate.start() - leadIn);
        }
        Double cut = nearestSceneCut(sceneCuts, candidate.start() - tolerance, candidate.start(), candidate.start());
        if (cut != null) {
            return cut;
        }
        return Math.max(0, candidate.start() - Math.min(leadIn, gapBefore(words, candidate.firstWordIndex())));
    }

    private double refineEnd(Candidate candidate, List<Word> words, List<Silence> silences, List<Double> sceneCuts,
            double tail, double tolerance) {
        Silence pause = nearestSilenceStartingAfter(silences, candidate.end(), tolerance);
        if (pause != null) {
            return Math.min(pause.end(), pause.start() + tail);
        }
        Double cut = nearestSceneCut(sceneCuts, candidate.end(), candidate.end() + tolerance, candidate.end());
        if (cut != null) {
            return cut;
        }
        return candidate.end() + Math.min(tail, gapAfter(words, candidate.lastWordIndex()));
    }

    private Silence nearestSilenceEndingNear(List<Silence> silences, double target, double tolerance) {
        Silence best = null;
        double bestDelta = Double.MAX_VALUE;
        for (Silence silence : silences) {
            double delta = Math.abs(silence.end() - target);
            if (delta <= tolerance && delta < bestDelta) {
                best = silence;
                bestDelta = delta;
            }
        }
        return best;
    }

    private Silence nearestSilenceStartingAfter(List<Silence> silences, double from, double tolerance) {
        Silence best = null;
        double bestDelta = Double.MAX_VALUE;
        for (Silence silence : silences) {
            double delta = silence.start() - from;
            if (delta >= 0 && delta <= tolerance && delta < bestDelta) {
                best = silence;
                bestDelta = delta;
            }
        }
        return best;
    }

    private Double nearestSceneCut(List<Double> sceneCuts, double low, double high, double anchor) {
        Double best = null;
        double bestDelta = Double.MAX_VALUE;
        for (double cut : sceneCuts) {
            if (cut < low || cut > high) {
                continue;
            }
            double delta = Math.abs(cut - anchor);
            if (delta < bestDelta) {
                best = cut;
                bestDelta = delta;
            }
        }
        return best;
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

    public record Bounds(double start, double end) {
    }
}
