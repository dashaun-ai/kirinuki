package ai.dashaun.kirinuki.vision;

import java.util.List;

public record ScreenAnalysis(ScreenRegion screenRegion, List<Double> screenChanges, boolean hasCode) {
}
