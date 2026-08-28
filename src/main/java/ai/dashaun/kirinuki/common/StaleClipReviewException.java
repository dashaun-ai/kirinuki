package ai.dashaun.kirinuki.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class StaleClipReviewException extends KirinukiException {

    public StaleClipReviewException(UUID videoId, int clipIndex, Throwable cause) {
        super(HttpStatus.CONFLICT, "Clip changed since you loaded it",
                "Clip %d of video %s was edited by someone else. Reload the clip and apply your change again."
                        .formatted(clipIndex, videoId),
                cause);
    }
}
