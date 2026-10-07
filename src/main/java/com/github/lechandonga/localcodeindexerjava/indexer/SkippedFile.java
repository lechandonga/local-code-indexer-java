package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 被跳过文件的记录：相对路径 + 稳定原因码 + 人类可读说明。
 */
public record SkippedFile(String path, String reason, String detail) {

    static SkippedFile of(String relativePath, SkipReason reason, String detail) {
        return new SkippedFile(relativePath, reason.code(), detail == null ? reason.description() : detail);
    }
}
