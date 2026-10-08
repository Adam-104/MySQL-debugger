package com.mysqldebugger.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(InvalidQueryException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_QUERY", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleBadBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Request body must be JSON like {\"query\": \"SELECT ...\"}.");
    }

    /** MySQL syntax errors, unknown tables/columns, connection problems, ... */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDatabase(DataAccessException ex) {
        return build(HttpStatus.BAD_REQUEST, "DATABASE_ERROR",
                ex.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_PARSE_ERROR", ex.getMessage());
    }

    private ResponseEntity<Map<String, String>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", code, "message", String.valueOf(message)));
    }
}
