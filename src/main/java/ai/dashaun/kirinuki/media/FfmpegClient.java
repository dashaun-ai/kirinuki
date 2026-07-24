package ai.dashaun.kirinuki.media;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class FfmpegClient {
    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;

    public FfmpegClient(KirinukiPipelineProperties properties, ProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    public void extractAudio(Path source, Path target) {
        String binary = properties.media().binary();
        processRunner.run(binary, List.of(binary,
                "-y",
                "-i", source.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                target.toString()), properties.media().timeout());
    }
}
