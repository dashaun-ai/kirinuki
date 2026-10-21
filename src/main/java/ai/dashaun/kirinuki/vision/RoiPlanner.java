package ai.dashaun.kirinuki.vision;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;
import ai.dashaun.kirinuki.media.FramingPlan;
import ai.dashaun.kirinuki.media.MediaProfile;
import ai.dashaun.kirinuki.media.SpeakerSegment;

@Component
public class RoiPlanner {

    private final DominantSpeakerSelector dominantSpeakerSelector;

    public RoiPlanner(DominantSpeakerSelector dominantSpeakerSelector) {
        this.dominantSpeakerSelector = dominantSpeakerSelector;
    }

    public Optional<FramingPlan> plan(ScreenAnalysis screen, FaceTracks faces, List<Word> words, double clipStart,
            double clipEnd, MediaProfile profile, KirinukiPipelineProperties properties) {
        if (screen != null && screen.hasCode() && screen.screenRegion() != null && profile != null) {
            return planForCode(screen.screenRegion(), clipStart, clipEnd, profile, properties);
        }
        if (faces == null || faces.tracks().isEmpty()) {
            return Optional.empty();
        }
        String framing = properties.render().framing();
        if ("active-speaker".equals(framing)) {
            return dominantSpeakerSelector.select(faces.tracks(), words, clipStart, clipEnd,
                    faces.frameWidth(), faces.frameHeight(), properties.render().reframe())
                    .map(region -> FramingPlan.single(region.width(), region.height(),
                            new SpeakerSegment(clipStart, clipEnd, region.x(), region.y())));
        }
        if ("follow-speaker".equals(framing) || "content-aware".equals(framing)) {
            return dominantSpeakerSelector.plan(faces.tracks(), words, clipStart, clipEnd,
                    faces.frameWidth(), faces.frameHeight(), properties.render().reframe());
        }
        return Optional.empty();
    }

    private Optional<FramingPlan> planForCode(ScreenRegion region, double clipStart, double clipEnd,
            MediaProfile profile, KirinukiPipelineProperties properties) {
        int frameWidth = profile.width();
        int frameHeight = profile.height();
        if (frameWidth <= 0 || frameHeight <= 0) {
            return Optional.empty();
        }

        int regionX = (int) Math.round(region.x() * frameWidth);
        int regionY = (int) Math.round(region.y() * frameHeight);
        int regionW = (int) Math.round(region.w() * frameWidth);
        int regionH = (int) Math.round(region.h() * frameHeight);

        double codeMargin = properties.render().reframe().codeMargin();
        int margin = (int) Math.round(codeMargin * Math.min(frameWidth, frameHeight));

        int paddedW = Math.max(1, regionW + 2 * margin);
        int paddedH = Math.max(1, regionH + 2 * margin);

        double aspect = properties.render().reframe().aspect();

        int cropW;
        int cropH;
        if (paddedW * aspect >= paddedH) {
            cropW = paddedW;
            cropH = (int) Math.round(paddedW * aspect);
        } else {
            cropH = paddedH;
            cropW = (int) Math.round(paddedH / aspect);
        }

        if (cropW > frameWidth) {
            cropW = frameWidth;
            cropH = (int) Math.round(cropW * aspect);
        }
        if (cropH > frameHeight) {
            cropH = frameHeight;
            cropW = (int) Math.round(cropH / aspect);
        }

        cropW = Math.min(cropW, frameWidth);
        cropH = Math.min(cropH, frameHeight);

        int regionCenterX = regionX + regionW / 2;
        int regionCenterY = regionY + regionH / 2;

        int cropX = regionCenterX - cropW / 2;
        int cropY = regionCenterY - cropH / 2;

        cropX = clamp(cropX, 0, frameWidth - cropW);
        cropY = clamp(cropY, 0, frameHeight - cropH);

        if (cropW <= 0 || cropH <= 0) {
            return Optional.empty();
        }

        return Optional.of(FramingPlan.single(cropW, cropH,
                new SpeakerSegment(clipStart, clipEnd, cropX, cropY)));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
