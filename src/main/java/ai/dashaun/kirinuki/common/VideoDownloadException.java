package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class VideoDownloadException extends KirinukiException {

    public VideoDownloadException(String message) {
        super(HttpStatus.BAD_GATEWAY, "Video download failed", message);
    }

    public VideoDownloadException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "Video download failed", message, cause);
    }
}
