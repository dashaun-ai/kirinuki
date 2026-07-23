package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class InvalidVideoUrlException extends KirinukiException {

    public InvalidVideoUrlException(String url) {
        super(HttpStatus.BAD_REQUEST, "Invalid video URL", "Not a recognisable YouTube video URL: " + url);
    }
}
