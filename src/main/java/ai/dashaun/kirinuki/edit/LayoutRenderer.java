package ai.dashaun.kirinuki.edit;

import java.nio.file.Path;
import java.util.List;

import ai.dashaun.kirinuki.candidate.Word;
import ai.dashaun.kirinuki.vision.Scene;

public interface LayoutRenderer {

    boolean supports(ShotType type);

    void render(Path source, List<Word> words, double clipStart, double clipDuration, Shot shot, Scene scene,
            Path output);
}
