package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class ExternalToolException extends KirinukiException {

    public ExternalToolException(String tool, String detail) {
        super(HttpStatus.BAD_GATEWAY, "External tool failed", tool + " " + detail);
    }

    public ExternalToolException(String tool, String detail, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "External tool failed", tool + " " + detail, cause);
    }
}
