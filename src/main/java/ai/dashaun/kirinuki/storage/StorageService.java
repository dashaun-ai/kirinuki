package ai.dashaun.kirinuki.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiStorageProperties;

@Service
public class StorageService {

    private static final String TEMPORARY_SUFFIX = ".part";
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Path root;

    public StorageService(KirinukiStorageProperties properties) {
        Objects.requireNonNull(properties, "kirinuki.storage properties must be set");
        this.root = Objects.requireNonNull(properties.root(), "kirinuki.storage.root must be set")
                .toAbsolutePath().normalize();
    }

    public Path videoDirectory(String videoId) {
        return safeResolve(root, Objects.requireNonNull(videoId, "videoId must not be null"));
    }

    public Path resolve(String videoId, String artifact) {
        return safeResolve(videoDirectory(videoId), Objects.requireNonNull(artifact, "artifact must not be null"));
    }

    public boolean exists(String videoId, String artifact) {
        return Files.exists(resolve(videoId, artifact));
    }

    public Path prepareFor(String videoId, String artifact) {
        Path target = resolve(videoId, artifact);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException exception) {
            throw new KirinukiException("Could not prepare storage path for " + target, exception);
        }
        return target;
    }

    public Path temporaryFor(String videoId, String artifact) {
        return prepareFor(videoId, temporaryName(artifact));
    }

    public void commit(String videoId, String artifact) {
        Path temporary = resolve(videoId, temporaryName(artifact));
        Path target = resolve(videoId, artifact);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new KirinukiException("Could not commit " + artifact + " for video " + videoId, exception);
        }
    }

    public void discardTemporary(String videoId, String artifact) {
        try {
            Files.deleteIfExists(resolve(videoId, temporaryName(artifact)));
        } catch (IOException exception) {
            log.warn("Could not discard partial {} for video {}", artifact, videoId, exception);
        }
    }

    private Path safeResolve(Path base, String segment) {
        Path resolved = base.resolve(segment).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new KirinukiException("Invalid path segment: " + segment);
        }
        return resolved;
    }

    private String temporaryName(String artifact) {
        int extension = artifact.lastIndexOf('.');
        return extension < 0
                ? artifact + TEMPORARY_SUFFIX
                : artifact.substring(0, extension) + TEMPORARY_SUFFIX + artifact.substring(extension);
    }
}
