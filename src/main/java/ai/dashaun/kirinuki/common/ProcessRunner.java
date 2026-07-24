package ai.dashaun.kirinuki.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {
    public String run(String tool, List<String> command, Duration timeout) {
        Process process = start(tool, command);
        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> standardOutput = drains.submit(() -> readFully(process.getInputStream()));
            Future<String> errorOutput = drains.submit(() -> readFully(process.getErrorStream()));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ExternalToolException(tool, "timed out after " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new ExternalToolException(tool,
                        "exited with %d: %s".formatted(process.exitValue(), errorOutput.get().strip()));
            }
            return standardOutput.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ExternalToolException(tool, "was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new ExternalToolException(tool, "output could not be read", exception.getCause());
        }
    }

    private Process start(String tool, List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new ToolNotAvailableException(tool, exception);
        }
    }

    private String readFully(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
