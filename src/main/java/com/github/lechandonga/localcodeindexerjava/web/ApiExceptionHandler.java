package com.github.lechandonga.localcodeindexerjava.web;

import com.github.lechandonga.localcodeindexerjava.index.IndexRefreshException;
import com.github.lechandonga.localcodeindexerjava.index.RefreshAlreadyRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把内部异常归一化成稳定的错误信封：{@code {errorCode,message,status,timestamp}}，
 * 不向调用方透传原始异常堆栈或底层路径敏感信息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(RefreshAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(RefreshAlreadyRunningException e) {
        return error(HttpStatus.CONFLICT, "REFRESH_ALREADY_RUNNING", e.getMessage());
    }

    @ExceptionHandler(IndexRefreshException.class)
    public ResponseEntity<Map<String, Object>> handleRefreshFailure(IndexRefreshException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "an unexpected internal error occurred");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", code);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
