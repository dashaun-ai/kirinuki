package ai.dashaun.kirinuki.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.springframework.stereotype.Component;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

@Component
public class WhisperClient {
    private final KirinukiPipelineProperties properties;
    private final ProcessRunner processRunner;

    public WhisperClient(KirinukiPipelineProperties properties, ProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    public void transcribe(Path audio, Path target) {
        KirinukiPipelineProperties.Asr asr = properties.asr();
        processRunner.run(asr.binary(), List.of(asr.binary(),
                audio.toString(),
                "--model", asr.model(),
                "--device", asr.device(),
                "--compute_type", asr.computeType(),
                "--word_timestamps", "True",
                "--output_format", "json",
                "--output_dir", target.getParent().toString()), asr.timeout());
        renameToTarget(audio, target);
    }

    private void renameToTarget(Path audio, Path target) {
        String audioName = audio.getFileName().toString();
        String baseName = audioName.substring(0, audioName.lastIndexOf('.'));
        Path produced = target.getParent().resolve(baseName + ".json");
        try {
            Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new KirinukiException("Could not move transcript from " + produced + " to " + target, exception);
        }
    }
}
