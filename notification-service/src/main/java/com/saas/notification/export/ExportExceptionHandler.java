package com.saas.notification.export;

import com.saas.notification.security.AuthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Maps the export API's own failures to status codes with a message that is ours and safe to show. Scoped
 * to {@link ExportController}; anything unexpected is left to Spring's default (a 500 that carries no
 * exception detail).
 */
@RestControllerAdvice(assignableTypes = ExportController.class)
public class ExportExceptionHandler {

    @ExceptionHandler(AuthenticationRequiredException.class)
    ResponseEntity<Map<String, String>> unauthenticated(AuthenticationRequiredException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badFilter(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "the request is not valid");
    }

    @ExceptionHandler(ActivityLogExportService.ExportTooLargeException.class)
    ResponseEntity<Map<String, String>> tooLarge(ActivityLogExportService.ExportTooLargeException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ActivityLogExportService.ExportNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ActivityLogExportService.ExportNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ActivityLogExportService.ExportNotReadyException.class)
    ResponseEntity<Map<String, String>> notReady(ActivityLogExportService.ExportNotReadyException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
