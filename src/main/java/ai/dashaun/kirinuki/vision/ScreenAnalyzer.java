package ai.dashaun.kirinuki.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import tools.jackson.databind.ObjectMapper;

import static org.bytedeco.opencv.global.opencv_core.sumElems;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.Canny;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

@Component
public class ScreenAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ScreenAnalyzer.class);

    private static final int GRID = 12;
    private static final double EDGE_THRESHOLD = 0.04;
    private static final double CHANGE_THRESHOLD = 0.08;
    private static final double CODE_THRESHOLD = 0.02;
    private static final int MAX_WIDTH = 1920;

    private final ProcessRunner processRunner;
    private final String binary;
    private final double intervalSeconds;
    private final double minChangeGapSeconds;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public ScreenAnalyzer(ProcessRunner processRunner, KirinukiPipelineProperties properties, ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.binary = properties.media().binary();
        this.intervalSeconds = properties.screen().interval().toMillis() / 1000.0;
        this.minChangeGapSeconds = properties.screen().minChangeGap().toMillis() / 1000.0;
        this.timeout = properties.screen().timeout();
        this.objectMapper = objectMapper;
    }

    public void analyze(Path source, Path target) {
        if (!Files.exists(source)) {
            throw new KirinukiException("Source video not found: " + source);
        }
        log.info("Analyzing screen regions for {}", source.getFileName());
        Path work = null;
        try {
            work = Files.createTempDirectory("kirinuki-screen");
            extractFrames(source, work);
            List<Path> framePaths = listFrames(work);
            if (framePaths.isEmpty()) {
                writeEmpty(target);
                return;
            }
            List<Mat> frames = readFrames(framePaths);
            List<Double> timestamps = buildTimestamps(framePaths.size());
            ScreenAnalysis analysis = analyzeFrames(frames, timestamps);
            release(frames);
            Files.writeString(target, objectMapper.writeValueAsString(analysis));
            log.info("Screen analysis for {}: region={} changes={} hasCode={}",
                    source.getFileName(), analysis.screenRegion(), analysis.screenChanges().size(), analysis.hasCode());
        } catch (Exception exception) {
            throw new KirinukiException("Could not analyze screen for " + source, exception);
        } finally {
            deleteRecursively(work);
        }
    }

    private void extractFrames(Path source, Path outputDir) {
        processRunner.run("ffmpeg", List.of(binary,
                "-y",
                "-hide_banner",
                "-nostats",
                "-i", source.toString(),
                "-vf", "fps=1/%s,scale='min(%d,iw):-2'".formatted(intervalSeconds, MAX_WIDTH),
                "-q:v", "2",
                outputDir.resolve("frame-%05d.jpg").toString()), timeout);
    }

    private List<Path> listFrames(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().matches("frame-\\d{5}\\.jpg"))
                    .sorted()
                    .toList();
        }
    }

    private List<Mat> readFrames(List<Path> paths) {
        List<Mat> frames = new ArrayList<>();
        for (Path path : paths) {
            Mat frame = imread(path.toString());
            if (!frame.empty()) {
                frames.add(frame);
            }
        }
        return frames;
    }

    private List<Double> buildTimestamps(int count) {
        List<Double> timestamps = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            timestamps.add(round(index * intervalSeconds));
        }
        return timestamps;
    }

    private ScreenAnalysis analyzeFrames(List<Mat> frames, List<Double> timestamps) {
        if (frames.isEmpty()) {
            return emptyAnalysis();
        }
        List<ScreenRegion> regions = new ArrayList<>();
        for (Mat frame : frames) {
            ScreenRegion region = detectRegion(frame);
            if (region != null) {
                regions.add(region);
            }
        }
        ScreenRegion region = aggregate(regions);
        List<Double> changes = region != null ? detectChanges(frames, region, timestamps) : List.of();
        boolean hasCode = region != null && hasCodeLikeContent(frames, region);
        return new ScreenAnalysis(region, changes, hasCode);
    }

    private ScreenRegion detectRegion(Mat frame) {
        Mat gray = new Mat();
        Mat edges = new Mat();
        try {
            cvtColor(frame, gray, COLOR_BGR2GRAY);
            Canny(gray, edges, 50, 150);
            int height = edges.rows();
            int width = edges.cols();
            int cellH = height / GRID;
            int cellW = width / GRID;
            if (cellH == 0 || cellW == 0) {
                return null;
            }
            double[][] densities = new double[GRID][GRID];
            for (int row = 0; row < GRID; row++) {
                for (int col = 0; col < GRID; col++) {
                    int y0 = row * cellH;
                    int y1 = Math.min((row + 1) * cellH, height);
                    int x0 = col * cellW;
                    int x1 = Math.min((col + 1) * cellW, width);
                    densities[row][col] = edgeDensity(edges, y0, y1, x0, x1);
                }
            }
            int minRow = GRID;
            int maxRow = -1;
            int minCol = GRID;
            int maxCol = -1;
            for (int row = 0; row < GRID; row++) {
                for (int col = 0; col < GRID; col++) {
                    if (densities[row][col] > EDGE_THRESHOLD) {
                        minRow = Math.min(minRow, row);
                        maxRow = Math.max(maxRow, row);
                        minCol = Math.min(minCol, col);
                        maxCol = Math.max(maxCol, col);
                    }
                }
            }
            if (maxRow < 0) {
                return null;
            }
            return new ScreenRegion(
                    round((double) minCol / GRID),
                    round((double) minRow / GRID),
                    round((double) (maxCol - minCol + 1) / GRID),
                    round((double) (maxRow - minRow + 1) / GRID));
        } finally {
            gray.release();
            edges.release();
        }
    }

    private double edgeDensity(Mat edges, int y0, int y1, int x0, int x1) {
        Mat cell = edges.rowRange(y0, y1).colRange(x0, x1);
        double sum = sumElems(cell).get(0);
        int size = cell.rows() * cell.cols();
        return size == 0 ? 0.0 : sum / (size * 255.0);
    }

    private ScreenRegion aggregate(List<ScreenRegion> regions) {
        if (regions.isEmpty()) {
            return null;
        }
        double[] xs = regions.stream().mapToDouble(ScreenRegion::x).sorted().toArray();
        double[] ys = regions.stream().mapToDouble(ScreenRegion::y).sorted().toArray();
        double[] ws = regions.stream().mapToDouble(ScreenRegion::w).sorted().toArray();
        double[] hs = regions.stream().mapToDouble(ScreenRegion::h).sorted().toArray();
        return new ScreenRegion(median(xs), median(ys), median(ws), median(hs));
    }

    private double median(double[] sorted) {
        int length = sorted.length;
        if (length % 2 == 1) {
            return sorted[length / 2];
        }
        return (sorted[length / 2 - 1] + sorted[length / 2]) / 2.0;
    }

    private List<Double> detectChanges(List<Mat> frames, ScreenRegion region, List<Double> timestamps) {
        List<Double> changes = new ArrayList<>();
        Mat previous = new Mat();
        double lastChange = -minChangeGapSeconds;
        for (int index = 0; index < frames.size(); index++) {
            Mat crop = cropRegion(frames.get(index), region);
            Mat gray = new Mat();
            cvtColor(crop, gray, COLOR_BGR2GRAY);
            if (!previous.empty()) {
                Mat diff = new Mat();
                org.bytedeco.opencv.global.opencv_core.absdiff(gray, previous, diff);
                double mean = org.bytedeco.opencv.global.opencv_core.mean(diff).get(0) / 255.0;
                double time = timestamps.get(index);
                if (mean > CHANGE_THRESHOLD && time - lastChange >= minChangeGapSeconds) {
                    changes.add(round(time));
                    lastChange = time;
                }
                diff.release();
            }
            previous.release();
            gray.copyTo(previous);
            crop.release();
        }
        previous.release();
        return changes;
    }

    private boolean hasCodeLikeContent(List<Mat> frames, ScreenRegion region) {
        List<Double> scores = new ArrayList<>();
        int limit = Math.min(frames.size(), 10);
        for (int index = 0; index < limit; index++) {
            Mat crop = cropRegion(frames.get(index), region);
            Mat gray = new Mat();
            Mat edges = new Mat();
            try {
                cvtColor(crop, gray, COLOR_BGR2GRAY);
                Canny(gray, edges, 50, 150);
                scores.add(horizontalEdgeScore(edges));
            } finally {
                gray.release();
                edges.release();
                crop.release();
            }
        }
        if (scores.isEmpty()) {
            return false;
        }
        scores.sort(Comparator.naturalOrder());
        return median(scores.stream().mapToDouble(Double::doubleValue).sorted().toArray()) > CODE_THRESHOLD;
    }

    private double horizontalEdgeScore(Mat edges) {
        int height = edges.rows();
        int width = edges.cols();
        if (height == 0 || width == 0) {
            return 0.0;
        }
        long runs = 0;
        for (int row = 0; row < height; row++) {
            boolean inRun = false;
            for (int col = 0; col < width; col++) {
                if (edges.ptr(row, col).get() != 0) {
                    if (!inRun) {
                        runs++;
                        inRun = true;
                    }
                } else {
                    inRun = false;
                }
            }
        }
        return (double) runs / (height * width);
    }

    private Mat cropRegion(Mat frame, ScreenRegion region) {
        int width = frame.cols();
        int height = frame.rows();
        int x0 = (int) Math.round(region.x() * width);
        int y0 = (int) Math.round(region.y() * height);
        int x1 = (int) Math.round((region.x() + region.w()) * width);
        int y1 = (int) Math.round((region.y() + region.h()) * height);
        x0 = Math.max(0, Math.min(x0, width));
        y0 = Math.max(0, Math.min(y0, height));
        x1 = Math.max(x0, Math.min(x1, width));
        y1 = Math.max(y0, Math.min(y1, height));
        return frame.rowRange(y0, y1).colRange(x0, x1);
    }

    private void release(List<Mat> frames) {
        for (Mat frame : frames) {
            frame.release();
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Could not delete temporary file {}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Could not clean up temporary directory {}", root, exception);
        }
    }

    private void writeEmpty(Path target) throws IOException {
        Files.writeString(target, objectMapper.writeValueAsString(emptyAnalysis()));
    }

    private ScreenAnalysis emptyAnalysis() {
        return new ScreenAnalysis(null, List.of(), false);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
