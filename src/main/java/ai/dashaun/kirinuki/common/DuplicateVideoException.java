package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class DuplicateVideoException extends KirinukiException {

    public DuplicateVideoException(String youtubeId) {
        super(HttpStatus.CONFLICT, "Video already ingested", "YouTube video already ingested: " + youtubeId);
    }
}
