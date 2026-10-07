package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 索引器内部使用的归一化异常。携带稳定错误码；
 * 当用于表达“跳过单个文件”时额外携带 {@link SkipReason}。
 */
public class IndexerException extends RuntimeException {

    private final IndexerErrorCode errorCode;
    private final transient SkipReason reason;

    public IndexerException(IndexerErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.reason = null;
    }

    public IndexerException(IndexerErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.reason = null;
    }

    public IndexerException(IndexerErrorCode errorCode, SkipReason reason, String message) {
        super(message);
        this.errorCode = errorCode;
        this.reason = reason;
    }

    public IndexerException(IndexerErrorCode errorCode, SkipReason reason, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.reason = reason;
    }

    public IndexerErrorCode errorCode() {
        return errorCode;
    }

    public SkipReason reason() {
        return reason;
    }
}
