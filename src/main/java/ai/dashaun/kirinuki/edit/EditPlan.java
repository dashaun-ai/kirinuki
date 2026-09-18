package ai.dashaun.kirinuki.edit;

import java.util.List;

public record EditPlan(int clipIndex, String theme, List<Shot> shots, List<Emphasis> emphasis) {
}
