package ai.dashaun.kirinuki.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import ai.dashaun.kirinuki.common.KirinukiException;

@ControllerAdvice(assignableTypes = DashboardController.class)
class DashboardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardExceptionHandler.class);

    @ExceptionHandler(KirinukiException.class)
    ModelAndView handleKirinuki(KirinukiException exception) {
        return errorView(exception.getStatus(), exception.getTitle(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ModelAndView handleUnexpected(Exception exception) {
        log.error("Unhandled dashboard exception", exception);
        return errorView(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "An unexpected error occurred.");
    }

    private ModelAndView errorView(HttpStatus status, String title, String detail) {
        ModelAndView modelAndView = new ModelAndView("dashboard-error");
        modelAndView.setStatus(status);
        modelAndView.addObject("status", status.value());
        modelAndView.addObject("title", title);
        modelAndView.addObject("detail", detail);
        return modelAndView;
    }
}
