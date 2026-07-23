package ai.dashaun.kirinuki.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import ai.dashaun.kirinuki.common.KirinukiException;
import ai.dashaun.kirinuki.config.KirinukiStorageProperties;

@Service
public class StorageService {

    private final Path root;

    public StorageService(KirinukiStorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    public Path videoDirectory(String videoId) {
        return root.resolve(videoId);
    }

    public Path resolve(String videoId, String artifact) {
        return videoDirectory(videoId).resolve(artifact);
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
}
