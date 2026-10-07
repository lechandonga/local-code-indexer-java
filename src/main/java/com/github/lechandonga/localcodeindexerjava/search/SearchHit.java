package com.github.lechandonga.localcodeindexerjava.search;

/**
 * 单条命中：文件相对路径、行号（从 1 开始）、命中模式、命中文本片段、命中在片段中的起始列。
 */
public record SearchHit(
        String file,
        int line,
        String mode,
        String matchedTerm,
        int matchStartColumn,
        String fragment,
        int fragmentStartColumn,
        long revision) {
}
