package ai.dashaun.kirinuki.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class SpeakerTracker {

    private static final double SPEECH_TOLERANCE = 0.5;
    private static final double MOTION_THRESHOLD = 1.0;
    private static final double MIN_SEGMENT_SECONDS = 1.0;

    private final KirinukiPipelineProperties properties;

    public SpeakerTracker(KirinukiPipelineProperties properties) {
        this.properties = properties;
    }

    public List<SpeakerActivity> track(FaceTracks faces, List<Word> words, double from, double to) {
        if (faces.tracks().isEmpty()) {
            return List.of();
        }

        List<List<TimeSample>> frames = groupByTime(collectSamples(faces, from, to));
        if (frames.isEmpty()) {
            return List.of();
        }

        List<MutableSegment> segments = new ArrayList<>();
        String currentSpeaker = null;
        double currentFrom = frames.get(0).get(0).t();

        for (List<TimeSample> frame : frames) {
            Optional<String> active = activeSpeaker(frame, words);
            String speaker = active.orElse(currentSpeaker != null ? currentSpeaker : largestFace(frame));
            if (speaker == null) {
                continue;
            }
            if (!speaker.equals(currentSpeaker)) {
                if (currentSpeaker != null) {
                    segments.add(new MutableSegment(currentFrom, frame.get(0).t(), currentSpeaker));
                }
                currentSpeaker = speaker;
                currentFrom = frame.get(0).t();
            }
        }
        if (currentSpeaker != null) {
            segments.add(new MutableSegment(currentFrom, to, currentSpeaker));
        }

        smooth(segments, MIN_SEGMENT_SECONDS);
        mergeSameSpeaker(segments);
        return segments.stream()
                .map(segment -> new SpeakerActivity(segment.from, segment.to, segment.speaker, 1.0))
                .toList();
    }

    private List<TimeSample> collectSamples(FaceTracks faces, double from, double to) {
        List<TimeSample> samples = new ArrayList<>();
        for (FaceTrack track : faces.tracks()) {
            for (FaceSample sample : track.samples()) {
                if (sample.t() >= from && sample.t() <= to) {
                    samples.add(new TimeSample(sample.t(), String.valueOf(track.id()), sample.motion(),
                            sample.width() * sample.height()));
                }
            }
        }
        samples.sort(Comparator.comparingDouble(TimeSample::t));
        return samples;
    }

    private List<List<TimeSample>> groupByTime(List<TimeSample> samples) {
        List<List<TimeSample>> frames = new ArrayList<>();
        List<TimeSample> current = new ArrayList<>();
        for (TimeSample sample : samples) {
            if (!current.isEmpty() && Math.abs(current.get(0).t() - sample.t()) > 0.001) {
                frames.add(current);
                current = new ArrayList<>();
            }
            current.add(sample);
        }
        if (!current.isEmpty()) {
            frames.add(current);
        }
        return frames;
    }

    private Optional<String> activeSpeaker(List<TimeSample> frame, List<Word> words) {
        double t = frame.get(0).t();
        boolean nearSpeech = words.stream()
                .anyMatch(word -> Math.abs((word.start() + word.end()) / 2.0 - t) <= SPEECH_TOLERANCE);
        if (!nearSpeech) {
            return Optional.empty();
        }
        return frame.stream()
                .filter(sample -> sample.motion() >= MOTION_THRESHOLD)
                .max(Comparator.comparingDouble(TimeSample::motion))
                .map(TimeSample::speakerId);
    }

    private String largestFace(List<TimeSample> frame) {
        return frame.stream()
                .max(Comparator.comparingDouble(TimeSample::area))
                .map(TimeSample::speakerId)
                .orElse(null);
    }

    private void smooth(List<MutableSegment> segments, double minDuration) {
        boolean changed = true;
        while (changed && segments.size() > 1) {
            changed = false;
            for (int index = 0; index < segments.size(); index++) {
                MutableSegment segment = segments.get(index);
                if (segment.duration() >= minDuration) {
                    continue;
                }
                MutableSegment previous = index > 0 ? segments.get(index - 1) : null;
                MutableSegment next = index + 1 < segments.size() ? segments.get(index + 1) : null;
                MutableSegment target = pickMergeTarget(previous, next);
                if (target == null) {
                    continue;
                }
                if (target == previous) {
                    previous.to = segment.to;
                } else {
                    next.from = segment.from;
                }
                segments.remove(index);
                changed = true;
                break;
            }
        }
    }

    private MutableSegment pickMergeTarget(MutableSegment previous, MutableSegment next) {
        if (previous == null) {
            return next;
        }
        if (next == null) {
            return previous;
        }
        if (previous.speaker.equals(next.speaker)) {
            return previous.duration() >= next.duration() ? previous : next;
        }
        return previous.duration() >= next.duration() ? previous : next;
    }

    private void mergeSameSpeaker(List<MutableSegment> segments) {
        boolean changed = true;
        while (changed && segments.size() > 1) {
            changed = false;
            for (int index = 1; index < segments.size(); index++) {
                MutableSegment previous = segments.get(index - 1);
                MutableSegment current = segments.get(index);
                if (previous.speaker.equals(current.speaker)) {
                    previous.to = current.to;
                    segments.remove(index);
                    changed = true;
                    break;
                }
            }
        }
    }

    private record TimeSample(double t, String speakerId, double motion, double area) {
    }

    private static class MutableSegment {

        private double from;
        private double to;
        private final String speaker;

        MutableSegment(double from, double to, String speaker) {
            this.from = from;
            this.to = to;
            this.speaker = speaker;
        }

        double duration() {
            return to - from;
        }
    }
}
