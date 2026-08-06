package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class ReviewNotReadyException extends KirinukiException {

    public ReviewNotReadyException(UUID videoId, String status) {
        super(HttpStatus.CONFLICT, "Video is not ready for review",
                "Video " + videoId + " is " + status + " and cannot be approved from review");
    }
}
