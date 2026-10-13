package ai.dashaun.kirinuki.edit;

import java.util.List;

public record Shot(
        double in,
        double out,
        ShotBlock block,
        String headline,
        String subline,
        List<String> lines,
        List<String> logos,
        String imageQuery,
        Double zoom,
        String transition) {
}
