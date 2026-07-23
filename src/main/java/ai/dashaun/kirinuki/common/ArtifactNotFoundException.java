package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class ArtifactNotFoundException extends KirinukiException {

    public ArtifactNotFoundException(UUID videoId, String artifact) {
        super(HttpStatus.NOT_FOUND, "Artifact not found",
                "Video " + videoId + " has not produced " + artifact + " yet");
    }
}
