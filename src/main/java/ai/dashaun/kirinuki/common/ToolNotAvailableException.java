package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class ToolNotAvailableException extends KirinukiException {

    public ToolNotAvailableException(String tool, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "External tool unavailable",
                tool + " could not be started — is it on PATH?", cause);
    }
}
