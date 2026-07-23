package ai.dashaun.kirinuki.common;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(KirinukiException.class)
    ProblemDetail handleKirinuki(KirinukiException exception) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problemDetail.setTitle(exception.getTitle());
        return problemDetail;
    }
}
