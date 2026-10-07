package com.github.lechandonga.localcodeindexerjava.web;

import com.github.lechandonga.localcodeindexerjava.indexer.IndexerErrorCode;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * 把所有异常归一化为稳定错误码响应；原始异常只进服务端日志，不返回给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IndexerException.class)
    public ResponseEntity<ApiError> handleIndexer(IndexerException ex, HttpServletRequest request) {
        IndexerErrorCode code = ex.errorCode();
        if (code == IndexerErrorCode.REFRESH_FAILED || code == IndexerErrorCode.SCAN_FAILED) {
            log.warn("索引操作失败 code={} path={}", code.code(), request.getRequestURI(), ex);
        }
        return ResponseEntity.status(code.httpStatus())
                .body(body(code.code(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String message = "参数 " + ex.getName() + " 取值非法";
        return ResponseEntity.badRequest()
                .body(body(IndexerErrorCode.INVALID_ARGUMENT.code(), message, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期的服务端错误 path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal_error", "服务内部错误，请查看服务端日志", request));
    }

    private static ApiError body(String code, String message, HttpServletRequest request) {
        return new ApiError(code, message, request.getRequestURI(), Instant.now());
    }
}
