package ai.dashaun.kirinuki.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
        List<String> command = new ArrayList<>();
        command.add(asr.binary());
        for (String argument : tokenize(asr.args())) {
            command.add(expand(argument, asr, audio, target));
        }
        processRunner.run(asr.binary(), command, asr.timeout());
        renameToTarget(audio, target);
    }

    private String expand(String argument, KirinukiPipelineProperties.Asr asr, Path audio, Path target) {
        Path outputDir = target.getParent();
        return argument
                .replace("{audio}", audio.toString())
                .replace("{model}", asr.model())
                .replace("{device}", asr.device())
                .replace("{computeType}", asr.computeType())
                .replace("{outputDir}", outputDir.toString())
                .replace("{outputBase}", outputDir.resolve(baseName(audio)).toString());
    }

    static List<String> tokenize(String template) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < template.length(); index++) {
            char character = template.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private void renameToTarget(Path audio, Path target) {
        Path produced = target.getParent().resolve(baseName(audio) + ".json");
        try {
            Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new KirinukiException("Could not move transcript from " + produced + " to " + target, exception);
        }
    }

    private String baseName(Path audio) {
        String audioName = audio.getFileName().toString();
        return audioName.substring(0, audioName.lastIndexOf('.'));
    }
}
