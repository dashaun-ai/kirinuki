package ai.dashaun.kirinuki.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.Region;
import ai.dashaun.kirinuki.media.SpeakerSegment;

@Component
public class DominantSpeakerSelector {

    private static final double SPEECH_TOLERANCE = 3.0;

    public Optional<Region> select(List<FaceTrack> tracks, List<Word> words, double clipStart, double clipEnd,
            int frameWidth, int frameHeight, KirinukiPipelineProperties.Render.Reframe reframe) {
        List<Double> speechTimes = wordTimes(words, clipStart, clipEnd);
        if (speechTimes.isEmpty()) {
            return Optional.empty();
        }
        List<FaceSample> inClip = inClip(tracks, clipStart, clipEnd);
        if (inClip.isEmpty()) {
            return Optional.empty();
        }
        double radius = radius(inClip, frameWidth);
        Cluster best = cluster(inClip, radius).stream()
                .map(cluster -> score(cluster, speechTimes))
                .max(Comparator.comparingDouble(Cluster::score).thenComparingDouble(Cluster::count))
                .orElse(null);
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(region(best, frameWidth, frameHeight, reframe));
    }

    public Optional<FramingPlan> plan(List<FaceTrack> tracks, List<Word> words, double clipStart, double clipEnd,
            int frameWidth, int frameHeight, KirinukiPipelineProperties.Render.Reframe reframe) {
        if (wordTimes(words, clipStart, clipEnd).isEmpty()) {
            return Optional.empty();
        }
        List<FaceSample> inClip = inClip(tracks, clipStart, clipEnd);
        if (inClip.isEmpty()) {
            return Optional.empty();
        }
        List<Cluster> clusters = cluster(inClip, radius(inClip, frameWidth));
        if (clusters.isEmpty()) {
            return Optional.empty();
        }
        double maxFaceWidth = clusters.stream().mapToDouble(cluster -> cluster.faceWidth).max().orElse(0);
        int width = (int) Math.round(clamp(maxFaceWidth * reframe.margin(), reframe.minWidth(), frameWidth));
        int height = (int) Math.round(clamp(width * reframe.aspect(), reframe.minHeight(), frameHeight));
        List<SpeakerSegment> segments = segments(inClip, clusters, clipStart, clipEnd,
                width, height, frameWidth, frameHeight, reframe);
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FramingPlan(width, height, segments));
    }

    private List<SpeakerSegment> segments(List<FaceSample> inClip, List<Cluster> clusters, double clipStart,
            double clipEnd, int width, int height, int frameWidth, int frameHeight,
            KirinukiPipelineProperties.Render.Reframe reframe) {
        Map<Double, SampleActivity> activeByTime = new TreeMap<>();
        for (FaceSample sample : inClip) {
            Cluster cluster = clusterOf(sample, clusters);
            SampleActivity existing = activeByTime.get(sample.t());
            if (existing == null || sample.motion() > existing.motion) {
                activeByTime.put(sample.t(), new SampleActivity(cluster, sample.motion()));
            }
        }

        List<MutableSegment> mutable = new ArrayList<>();
        Cluster current = null;
        for (Map.Entry<Double, SampleActivity> entry : activeByTime.entrySet()) {
            double time = entry.getKey();
            SampleActivity activity = entry.getValue();
            if (activity.motion <= 0) {
                continue;
            }
            if (activity.cluster == current) {
                continue;
            }
            if (current == null) {
                current = activity.cluster;
                mutable.add(new MutableSegment(time, time, current));
            } else {
                mutable.get(mutable.size() - 1).to = time;
                current = activity.cluster;
                mutable.add(new MutableSegment(time, time, current));
            }
        }

        if (mutable.isEmpty()) {
            Cluster largest = clusters.stream()
                    .max(Comparator.comparingInt(cluster -> cluster.samples.size()))
                    .orElse(clusters.get(0));
            return List.of(new SpeakerSegment(clipStart, clipEnd,
                    xFor(largest, width, frameWidth), yFor(largest, height, frameHeight)));
        }
        mutable.get(0).from = clipStart;
        mutable.get(mutable.size() - 1).to = clipEnd;
        smooth(mutable, reframe.minSegmentSeconds());

        return mutable.stream()
                .map(segment -> new SpeakerSegment(segment.from, segment.to,
                        xFor(segment.cluster, width, frameWidth), yFor(segment.cluster, height, frameHeight)))
                .toList();
    }

    private void smooth(List<MutableSegment> segments, double minDuration) {
        boolean changed = true;
        while (changed && segments.size() > 1) {
            changed = false;
            for (int index = 0; index < segments.size(); index++) {
                MutableSegment segment = segments.get(index);
                if (segment.to - segment.from >= minDuration) {
                    continue;
                }
                if (index > 0) {
                    segments.get(index - 1).to = segment.to;
                } else {
                    segments.get(index + 1).from = segment.from;
                }
                segments.remove(index);
                changed = true;
                break;
            }
        }
    }

    private List<FaceSample> inClip(List<FaceTrack> tracks, double clipStart, double clipEnd) {
        return tracks.stream()
                .flatMap(track -> track.samples().stream())
                .filter(sample -> sample.t() >= clipStart - SPEECH_TOLERANCE
                        && sample.t() <= clipEnd + SPEECH_TOLERANCE)
                .toList();
    }

    private double radius(List<FaceSample> samples, int frameWidth) {
        double averageFaceWidth = samples.stream().mapToDouble(FaceSample::width).average().orElse(0);
        return Math.max(0.08 * frameWidth, 1.5 * averageFaceWidth);
    }

    private Cluster clusterOf(FaceSample sample, List<Cluster> clusters) {
        Cluster nearest = clusters.get(0);
        double best = Double.MAX_VALUE;
        for (Cluster cluster : clusters) {
            double distance = Math.hypot(cluster.cx - sample.cx(), cluster.cy - sample.cy());
            if (distance < best) {
                best = distance;
                nearest = cluster;
            }
        }
        return nearest;
    }

    private int xFor(Cluster cluster, int width, int frameWidth) {
        return (int) Math.round(clamp(cluster.cx - width / 2.0, 0, frameWidth - width));
    }

    private int yFor(Cluster cluster, int height, int frameHeight) {
        return (int) Math.round(clamp(cluster.cy - height * 0.45, 0, frameHeight - height));
    }

    private List<Cluster> cluster(List<FaceSample> samples, double radius) {
        List<Cluster> clusters = new ArrayList<>();
        for (FaceSample sample : samples) {
            Cluster nearest = null;
            double bestDistance = radius;
            for (Cluster cluster : clusters) {
                double distance = Math.hypot(cluster.cx - sample.cx(), cluster.cy - sample.cy());
                if (distance < bestDistance) {
                    nearest = cluster;
                    bestDistance = distance;
                }
            }
            if (nearest == null) {
                nearest = new Cluster();
                clusters.add(nearest);
            }
            nearest.samples.add(sample);
            nearest.recomputeCentroid();
        }
        return clusters;
    }

    private Cluster score(Cluster cluster, List<Double> speechTimes) {
        double nearSpeech = cluster.samples.stream()
                .filter(sample -> nearSpeech(sample.t(), speechTimes))
                .mapToDouble(FaceSample::motion)
                .sum();
        cluster.score = nearSpeech > 0 ? nearSpeech
                : cluster.samples.stream().mapToDouble(FaceSample::motion).sum();
        return cluster;
    }

    private Region region(Cluster best, int frameWidth, int frameHeight,
            KirinukiPipelineProperties.Render.Reframe reframe) {
        int regionWidth = (int) Math.round(clamp(best.faceWidth * reframe.margin(),
                reframe.minWidth(), frameWidth));
        int regionHeight = (int) Math.round(clamp(regionWidth * reframe.aspect(),
                reframe.minHeight(), frameHeight));
        int x = (int) Math.round(clamp(best.cx - regionWidth / 2.0, 0, frameWidth - regionWidth));
        int y = (int) Math.round(clamp(best.cy - regionHeight * 0.45, 0, frameHeight - regionHeight));
        return new Region(x, y, regionWidth, regionHeight);
    }

    private List<Double> wordTimes(List<Word> words, double clipStart, double clipEnd) {
        return words.stream()
                .filter(word -> word.end() >= clipStart && word.start() <= clipEnd)
                .map(word -> (word.start() + word.end()) / 2)
                .toList();
    }

    private boolean nearSpeech(double time, List<Double> speechTimes) {
        return speechTimes.stream().anyMatch(speech -> Math.abs(speech - time) <= SPEECH_TOLERANCE);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SampleActivity(Cluster cluster, double motion) {
    }

    private static class MutableSegment {

        private double from;
        private double to;
        private final Cluster cluster;

        MutableSegment(double from, double to, Cluster cluster) {
            this.from = from;
            this.to = to;
            this.cluster = cluster;
        }
    }

    private static class Cluster {

        private final List<FaceSample> samples = new ArrayList<>();
        private double cx;
        private double cy;
        private double faceWidth;
        private double score;

        int count() {
            return samples.size();
        }

        double score() {
            return score;
        }

        void recomputeCentroid() {
            cx = samples.stream().mapToDouble(FaceSample::cx).average().orElse(0);
            cy = samples.stream().mapToDouble(FaceSample::cy).average().orElse(0);
            faceWidth = samples.stream().mapToDouble(FaceSample::width).average().orElse(0);
        }
    }
}
