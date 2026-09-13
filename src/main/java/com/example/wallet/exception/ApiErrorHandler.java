package com.example.wallet.exception;



import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException e) { return error(e.status(), e.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() { return error(400, "invalid_json"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType() { return error(415, "application_json_required"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method() { return error(405, "method_not_allowed"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing() { return error(404, "not_found"); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<?> database(RuntimeException e) {
        LOG.atError().addKeyValue("event", "database_error").addKeyValue("type", e.getClass().getSimpleName()).log("Database operation unavailable");
        return error(503, "service_unavailable");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        LOG.atError().addKeyValue("event", "unexpected_error").addKeyValue("type", e.getClass().getSimpleName()).log("Unexpected request failure");
        return error(500, "internal_error");
    }
    private ResponseEntity<?> error(int status, String code) {
        var response = ResponseEntity.status(status);
        if (status == 503) response.header("Retry-After", "1");
        return response.body(Map.of("error", code, "correlation_id", String.valueOf(MDC.get("correlation_id"))));
    }
}
