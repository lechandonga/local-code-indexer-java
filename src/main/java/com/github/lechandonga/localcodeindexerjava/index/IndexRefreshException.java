package com.github.lechandonga.localcodeindexerjava.index;

/**
 * 刷新流程的归一化异常：只携带稳定错误码与面向调用方的消息，不泄漏原始异常。
 */
public class IndexRefreshException extends RuntimeException {

    private final String errorCode;

    public IndexRefreshException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
