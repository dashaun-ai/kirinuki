package ai.dashaun.kirinuki.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.dashaun.kirinuki.common.ProcessRunner;
import ai.dashaun.kirinuki.config.KirinukiPipelineProperties;

class WhisperClientTest {

    private final ProcessRunner processRunner = mock(ProcessRunner.class);

    @TempDir
    Path directory;

    @Test
    void should_build_the_default_ctranslate2_command_from_the_args_template() throws IOException {
        Path audio = audio("audio.wav");
        Path target = Path.of(directory.toString(), "transcript.json");
        producedJson("audio.json", "{}");

        client(Map.of()).transcribe(audio, target);

        assertThat(command()).containsExactly("whisper-ctranslate2",
                audio.toString(),
                "--model", "base.en",
                "--device", "cpu",
                "--compute_type", "int8",
                "--word_timestamps", "True",
                "--output_format", "json",
                "--output_dir", directory.toString());
    }

    @Test
    void should_build_the_command_from_a_configured_args_template() throws IOException {
        Path audio = audio("audio.wav");
        Path target = Path.of(directory.toString(), "transcript.json");
        producedJson("audio.json", "{}");

        client(Map.of(
                "kirinuki.pipeline.asr.binary", "whisper-cli",
                "kirinuki.pipeline.asr.model", "/models/ggml-large-v3.bin",
                "kirinuki.pipeline.asr.args",
                "{audio} -m {model} -ml 1 -sow -oj -of {outputBase}")).transcribe(audio, target);

        assertThat(command()).containsExactly("whisper-cli",
                audio.toString(),
                "-m", "/models/ggml-large-v3.bin",
                "-ml", "1",
                "-sow",
                "-oj",
                "-of", Path.of(directory.toString(), "audio").toString());
    }

    @Test
    void should_keep_a_quoted_placeholder_with_spaces_as_a_single_argument() throws IOException {
        Path audio = audio("audio.wav");
        Path target = Path.of(directory.toString(), "transcript.json");
        producedJson("audio.json", "{}");
        Path model = Path.of(directory.toString(), "my models", "ggml-large-v3.bin");
        Files.createDirectories(model.getParent());

        client(Map.of(
                "kirinuki.pipeline.asr.binary", "whisper-cli",
                "kirinuki.pipeline.asr.model", model.toString(),
                "kirinuki.pipeline.asr.args",
                "{audio} -m \"{model}\" -oj -of {outputBase}")).transcribe(audio, target);

        assertThat(command()).containsSequence("-m", model.toString());
    }

    @Test
    void should_call_the_configured_binary_with_the_asr_timeout() throws IOException {
        Path audio = audio("audio.wav");
        Path target = Path.of(directory.toString(), "transcript.json");
        producedJson("audio.json", "{}");

        client(Map.of(
                "kirinuki.pipeline.asr.binary", "/opt/whisper/bin/whisper-cli",
                "kirinuki.pipeline.asr.timeout", "45m")).transcribe(audio, target);

        verify(processRunner).run(eq("/opt/whisper/bin/whisper-cli"), anyList(), eq(Duration.ofMinutes(45)));
    }

    @Test
    void should_rename_the_produced_json_to_the_target() throws IOException {
        Path audio = audio("audio.wav");
        Path target = Path.of(directory.toString(), "transcript.json");
        producedJson("audio.json", "{\"segments\": []}");

        client(Map.of()).transcribe(audio, target);

        assertThat(target).exists();
        assertThat(target.getParent().resolve("audio.json")).doesNotExist();
    }

    private WhisperClient client(Map<String, Object> configuration) {
        return new WhisperClient(new Binder(new MapConfigurationPropertySource(configuration))
                .bindOrCreate("kirinuki.pipeline", KirinukiPipelineProperties.class), processRunner);
    }

    private Path audio(String name) throws IOException {
        Path audio = directory.resolve(name);
        Files.writeString(audio, "not really audio");
        return audio;
    }

    private void producedJson(String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content);
    }

    private List<String> command() {
        ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
        verify(processRunner).run(anyString(), command.capture(), any(Duration.class));
        return command.getValue();
    }
}
