package com.saas.org.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import com.saas.org.leave.LeaveRequestNotFoundException;
import com.saas.org.leave.ModuleDisabledException;
import com.saas.org.service.LogoTooLargeException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException ex) {
        return ResponseEntity.status(403).body(errorBody(ex.getMessage()));
    }

    /** A business rule would be violated (e.g. demoting the last ADMIN, responding twice). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(409).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(errorBody(errors));
    }

    /** A request that reaches the service without the gateway's context header is a caller error, not a server fault. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(errorBody("missing header: " + ex.getHeaderName()));
    }

    /** An unreadable body, including a property the request does not define (e.g. a raw colour), is a caller error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(errorBody("invalid request body"));
    }

    /** A logo over the size limit (REQ-PERS-03): refused by the multipart limit or by the service's own check. */
    @ExceptionHandler({LogoTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, Object>> handleTooLarge(Exception ex) {
        return ResponseEntity.status(413).body(errorBody("the file is too large"));
    }

    /** No file part in a logo upload. */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleBadUpload(Exception ex) {
        return ResponseEntity.badRequest().body(errorBody("a file part named 'file' is required"));
    }

    /** A module that is off, or a request the caller may not see, does not exist for them (REQ-MOD-01, REQ-LEAVE-06). */
    @ExceptionHandler({ModuleDisabledException.class, LeaveRequestNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(404).body(errorBody(ex.getMessage()));
    }

    /** A path or query value that is not the type it must be (an id, a date, an enum constant) is a caller error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadParameter(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(errorBody("invalid value for " + ex.getName()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        // Spring's own client errors (no such route 404, wrong method 405, unsupported media type 415, ...)
        // carry their status; reporting them as 500 would hide caller mistakes as server faults
        if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode()).body(errorBody(ex.getMessage()));
        }
        log.error("Unexpected error", ex);
        return ResponseEntity.status(500).body(errorBody("Internal server error"));
    }

    private Map<String, Object> errorBody(String message) {
        return Map.of("success", false, "message", message,
                "timestamp", LocalDateTime.now().toString());
    }
}
