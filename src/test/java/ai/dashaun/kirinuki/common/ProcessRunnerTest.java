package ai.dashaun.kirinuki.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProcessRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProcessRunner processRunner = new ProcessRunner();

    @Test
    void should_return_the_standard_output_when_the_tool_succeeds() {
        String output = processRunner.run("sh", List.of("sh", "-c", "printf 'candidate scored'"), TIMEOUT);

        assertThat(output).isEqualTo("candidate scored");
    }

    @Test
    void should_drain_output_larger_than_the_pipe_buffer_without_deadlocking() {
        String output = processRunner.run("sh", List.of("sh", "-c", "yes kirinuki | head -c 200000"), TIMEOUT);

        assertThat(output).hasSize(200000);
    }

    @Test
    void should_report_the_exit_code_and_error_output_when_the_tool_fails() {
        assertThatExceptionOfType(ExternalToolException.class)
                .isThrownBy(() -> processRunner.run("ffmpeg",
                        List.of("sh", "-c", "printf 'no such filter' >&2; exit 3"), TIMEOUT))
                .withMessageContaining("ffmpeg exited with 3: no such filter");
    }

    @Test
    void should_report_the_tool_as_unavailable_when_the_binary_is_not_on_the_path() {
        assertThatExceptionOfType(ToolNotAvailableException.class)
                .isThrownBy(() -> processRunner.run("whisper-ctranslate2",
                        List.of("kirinuki-binary-that-does-not-exist"), TIMEOUT))
                .withMessageContaining("is it on PATH?");
    }

    @Test
    void should_kill_the_tool_when_it_outruns_its_timeout() {
        assertThatExceptionOfType(ExternalToolException.class)
                .isThrownBy(() -> processRunner.run("yt-dlp", List.of("sh", "-c", "exec sleep 30"),
                        Duration.ofMillis(200)))
                .withMessageContaining("yt-dlp timed out after PT0.2S");
    }
}
