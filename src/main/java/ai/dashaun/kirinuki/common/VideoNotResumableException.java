package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class VideoNotResumableException extends KirinukiException {

    public VideoNotResumableException(UUID videoId, String status) {
        super(HttpStatus.CONFLICT, "Video cannot be advanced",
                "Video " + videoId + " is " + status + " and will not be re-driven");
    }
}
