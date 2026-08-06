package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class ClipReviewNotFoundException extends KirinukiException {

    public ClipReviewNotFoundException(UUID videoId, int clipIndex) {
        super(HttpStatus.NOT_FOUND, "Clip not found", "Clip " + clipIndex + " not found for video " + videoId);
    }
}
