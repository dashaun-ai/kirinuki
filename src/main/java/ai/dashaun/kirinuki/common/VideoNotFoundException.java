package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class VideoNotFoundException extends KirinukiException {

    public VideoNotFoundException(UUID videoId) {
        super(HttpStatus.NOT_FOUND, "Video not found", "No video with id " + videoId);
    }
}
