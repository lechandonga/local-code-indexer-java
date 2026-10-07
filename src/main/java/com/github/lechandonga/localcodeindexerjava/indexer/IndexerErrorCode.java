package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 对外稳定的错误码。所有底层异常都会被归一化到这些码，
 * HTTP 响应体只暴露 {@link #code} 与安全的 message，不携带原始堆栈。
 */
public enum IndexerErrorCode {

    INVALID_ARGUMENT("invalid_argument", 400),
    REFRESH_ALREADY_RUNNING("refresh_already_running", 409),
    ROOT_NOT_FOUND("root_not_found", 422),
    ROOT_NOT_DIRECTORY("root_not_directory", 422),
    SCAN_FAILED("scan_failed", 500),
    REFRESH_FAILED("refresh_failed", 500),
    SKIP_FILE("skip_file", 0);

    private final String code;
    private final int httpStatus;

    IndexerErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
