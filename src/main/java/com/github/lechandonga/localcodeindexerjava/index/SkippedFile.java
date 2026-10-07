package com.github.lechandonga.localcodeindexerjava.index;

/**
 * 被跳过文件的记录：相对路径、稳定原因码、面向调用方的说明。
 */
public record SkippedFile(String path, SkipReason reason, String detail) {
}
