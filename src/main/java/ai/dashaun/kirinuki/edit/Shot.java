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
        String transition,
        ShotType type,
        String speaker,
        List<String> speakers,
        KineticSpec kinetic,
        Region focus) {

    public Shot withType(ShotType type) {
        return new Shot(in, out, block, headline, subline, lines, logos, imageQuery, zoom, transition, type, speaker,
                speakers, kinetic, focus);
    }

    public Shot withSpeaker(String speaker) {
        return new Shot(in, out, block, headline, subline, lines, logos, imageQuery, zoom, transition, type, speaker,
                speakers, kinetic, focus);
    }

    public Shot withKinetic(KineticSpec kinetic) {
        return new Shot(in, out, block, headline, subline, lines, logos, imageQuery, zoom, transition, type, speaker,
                speakers, kinetic, focus);
    }

    public Shot withFocus(Region focus) {
        return new Shot(in, out, block, headline, subline, lines, logos, imageQuery, zoom, transition, type, speaker,
                speakers, kinetic, focus);
    }
}
