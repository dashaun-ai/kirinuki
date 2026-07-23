package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class KirinukiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    protected KirinukiException(HttpStatus status, String title, String message) {
        super(message);
        this.status = status;
        this.title = title;
    }

    protected KirinukiException(HttpStatus status, String title, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.title = title;
    }

    public KirinukiException(String message) {
        this(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", message);
    }

    public KirinukiException(String message, Throwable cause) {
        this(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", message, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }
}
