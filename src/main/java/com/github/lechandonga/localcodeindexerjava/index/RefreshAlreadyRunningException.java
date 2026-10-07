package com.github.lechandonga.localcodeindexerjava.index;

/**
 * 已有刷新任务进行中，且调用方要求不复用时抛出。对应 HTTP 409。
 */
public class RefreshAlreadyRunningException extends RuntimeException {

    public RefreshAlreadyRunningException(String message) {
        super(message);
    }
}
