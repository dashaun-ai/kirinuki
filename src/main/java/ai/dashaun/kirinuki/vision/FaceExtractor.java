package ai.dashaun.kirinuki.vision;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FfmpegClient;
import tools.jackson.databind.ObjectMapper;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.absdiff;
import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromCaffe;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

@Component
public class FaceExtractor {

    private static final Logger log = LoggerFactory.getLogger(FaceExtractor.class);
    private static final String PROTOTXT_RESOURCE = "vision/face_detector.prototxt";
    private static final String MODEL_RESOURCE = "vision/res10_300x300_ssd_iter_140000.caffemodel";
    private static final int THUMBNAIL_WIDTH = 640;

    private final FfmpegClient ffmpegClient;
    private final KirinukiPipelineProperties properties;
    private final ObjectMapper objectMapper;

    public FaceExtractor(FfmpegClient ffmpegClient, KirinukiPipelineProperties properties, ObjectMapper objectMapper) {
        this.ffmpegClient = ffmpegClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void extract(Path source, Path target) {
        Path work = null;
        try {
            work = Files.createTempDirectory("kirinuki-faces");
            Path prototxt = work.resolve("face_detector.prototxt");
            Path model = work.resolve("face_detector.caffemodel");
            copyResources(prototxt, model);

            Path fullFrame = work.resolve("probe.jpg");
            ffmpegClient.extractFrame(source, 0, 0, fullFrame);
            Mat probe = imread(fullFrame.toString());
            int frameWidth = probe.cols();
            int frameHeight = probe.rows();
            probe.release();

            double interval = properties.visual().sampleInterval().toMillis() / 1000.0;
            ffmpegClient.extractThumbnails(source, interval, THUMBNAIL_WIDTH, work);
            List<Path> frames = framePaths(work);
            double scale = (double) frameWidth / THUMBNAIL_WIDTH;

            List<FaceTrack> tracks = detect(prototxt.toString(), model.toString(), frames, interval, scale);
            write(new FaceTracks(frameWidth, frameHeight, tracks), target);
        } catch (IOException exception) {
            throw new KirinukiException("Could not extract faces from " + source, exception);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    private List<FaceTrack> detect(String prototxtPath, String modelPath, List<Path> frames, double interval,
            double scale) {
        KirinukiPipelineProperties.Visual visual = properties.visual();
        Net net = readNetFromCaffe(prototxtPath, modelPath);
        List<MutableTrack> tracks = new ArrayList<>();
        double maxGap = 3 * interval;
        int minFaceWidth = (int) Math.round(visual.minFaceScale() * THUMBNAIL_WIDTH);
        float scoreThreshold = (float) visual.minConfidence();

        Mat gray = new Mat();
        Mat previousGray = new Mat();
        try {
            for (int index = 0; index < frames.size(); index++) {
                double t = index * interval;
                Mat image = imread(frames.get(index).toString());
                if (image.empty()) {
                    image.release();
                    continue;
                }
                cvtColor(image, gray, COLOR_BGR2GRAY);
                Mat blob = blobFromImage(image, 1.0, new Size(300, 300),
                        new Scalar(104.0, 177.0, 123.0, 0.0), false, false, CV_32F);
                net.setInput(blob);
                Mat output = net.forward();
                try {
                    if (output.total() > 0) {
                        associate(tracks, output, t, gray, previousGray, scale, minFaceWidth, scoreThreshold, maxGap);
                    }
                } finally {
                    blob.release();
                    output.release();
                    image.release();
                    gray.copyTo(previousGray);
                }
            }
        } finally {
            gray.release();
            previousGray.release();
            net.close();
        }

        return tracks.stream()
                .filter(track -> !track.samples.isEmpty())
                .sorted(Comparator.comparingInt(MutableTrack::id))
                .map(MutableTrack::toTrack)
                .toList();
    }

    private void associate(List<MutableTrack> tracks, Mat output, double t, Mat gray, Mat previousGray,
            double scale, int minFaceWidth, float scoreThreshold, double maxGap) {
        int rows = (int) output.size(2);
        int columns = (int) output.size(3);
        FloatPointer data = new FloatPointer(output.data());
        int frameWidth = gray.cols();
        int frameHeight = gray.rows();
        for (int row = 0; row < rows; row++) {
            int base = row * columns;
            float confidence = data.get(base + 2);
            if (confidence < scoreThreshold) {
                continue;
            }
            float width = (data.get(base + 5) - data.get(base + 3)) * frameWidth;
            float height = (data.get(base + 6) - data.get(base + 4)) * frameHeight;
            if (width < minFaceWidth || height <= 0) {
                continue;
            }
            float x = data.get(base + 3) * frameWidth;
            float y = data.get(base + 4) * frameHeight;
            double centerX = (x + width / 2.0) * scale;
            double centerY = (y + height / 2.0) * scale;
            double maxAssociation = Math.max(width * 1.5, frameWidth * 0.2) * scale;
            MutableTrack best = null;
            double bestDistance = maxAssociation;
            for (MutableTrack track : tracks) {
                FaceSample last = track.samples.get(track.samples.size() - 1);
                if (t - last.t() > maxGap) {
                    continue;
                }
                double distance = Math.hypot(last.cx() - centerX, last.cy() - centerY);
                if (distance < bestDistance) {
                    best = track;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                best = new MutableTrack(tracks.size() + 1);
                tracks.add(best);
            }
            double motion = mouthMotion(gray, previousGray, x, y, width, height);
            best.samples.add(new FaceSample(t, centerX, centerY, width * scale, height * scale, motion));
        }
    }

    private double mouthMotion(Mat gray, Mat previousGray, float x, float y, float width, float height) {
        if (previousGray.empty()) {
            return 0;
        }
        int x0 = (int) Math.max(0, x + width * 0.15);
        int y0 = (int) Math.max(0, y + height * 0.65);
        int x1 = (int) Math.min(gray.cols(), x + width * 0.85);
        int y1 = (int) Math.min(gray.rows(), y + height * 0.95);
        if (x1 - x0 < 2 || y1 - y0 < 2) {
            return 0;
        }
        Mat current = gray.rowRange(y0, y1).colRange(x0, x1);
        Mat previous = previousGray.rowRange(y0, y1).colRange(x0, x1);
        Mat difference = new Mat();
        try {
            absdiff(current, previous, difference);
            Scalar scalar = mean(difference);
            return scalar.get(0);
        } finally {
            current.release();
            previous.release();
            difference.release();
        }
    }

    private List<Path> framePaths(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().matches("frame-\\d{5}\\.jpg"))
                    .sorted()
                    .toList();
        }
    }

    private void copyResources(Path prototxt, Path model) throws IOException {
        try (InputStream input = new ClassPathResource(PROTOTXT_RESOURCE).getInputStream();
                OutputStream output = Files.newOutputStream(prototxt)) {
            input.transferTo(output);
        }
        try (InputStream input = new ClassPathResource(MODEL_RESOURCE).getInputStream();
                OutputStream output = Files.newOutputStream(model)) {
            input.transferTo(output);
        }
    }

    private void write(FaceTracks tracks, Path target) throws IOException {
        Files.writeString(target, objectMapper.writeValueAsString(tracks));
    }

    private void deleteRecursively(Path root) {
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

    private static class MutableTrack {

        private final int id;
        private final List<FaceSample> samples = new ArrayList<>();

        MutableTrack(int id) {
            this.id = id;
        }

        int id() {
            return id;
        }

        FaceTrack toTrack() {
            return new FaceTrack(id, samples);
        }
    }
}
