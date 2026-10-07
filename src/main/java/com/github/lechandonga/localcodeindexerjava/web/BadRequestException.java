package com.github.lechandonga.localcodeindexerjava.web;

/**
 * 请求参数非法，对应 HTTP 400。携带稳定错误码。
 */
public class BadRequestException extends RuntimeException {

    private final String errorCode;

    public BadRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
