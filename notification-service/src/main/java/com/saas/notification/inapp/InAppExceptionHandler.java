package com.saas.notification.inapp;

import com.saas.notification.security.AuthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Maps the notification API's own failures to status codes with a message that is ours and safe to show. */
@RestControllerAdvice(assignableTypes = InAppNotificationController.class)
public class InAppExceptionHandler {

    @ExceptionHandler(AuthenticationRequiredException.class)
    ResponseEntity<Map<String, String>> unauthenticated(AuthenticationRequiredException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> malformed(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "the request is not valid");
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotificationNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
