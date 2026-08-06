package ai.dashaun.kirinuki.common;

import org.springframework.http.HttpStatus;

public class InvalidRegenerationException extends KirinukiException {

    public InvalidRegenerationException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid regeneration request", message);
    }
}
