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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    public String run(String tool, List<String> command, Duration timeout) {
        log.debug("Running {}: {}", tool, String.join(" ", command));
        ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
        Process process = null;
        try {
            Process started = start(tool, command);
            process = started;
            Future<String> standardOutput = drains.submit(() -> readFully(started.getInputStream()));
            Future<String> errorOutput = drains.submit(() -> readFully(started.getErrorStream()));

            if (!started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ExternalToolException(tool, "timed out after " + timeout);
            }
            if (started.exitValue() != 0) {
                throw new ExternalToolException(tool,
                        "exited with %d: %s".formatted(started.exitValue(), errorOutput.get().strip()));
            }
            return standardOutput.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalToolException(tool, "was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new ExternalToolException(tool, "output could not be read", exception.getCause());
        } finally {
            if (process != null) {
                kill(process);
            }
            drains.close();
        }
    }

    private void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Process start(String tool, List<String> command) {
        try {
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            return process;
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
