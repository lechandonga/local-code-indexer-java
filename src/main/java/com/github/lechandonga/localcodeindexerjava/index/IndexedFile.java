package com.github.lechandonga.localcodeindexerjava.index;

import java.util.List;

/**
 * 单个已索引文件的不可变内容。{@code lines} 为按行切分后的文本，行号从 1 开始。
 */
public record IndexedFile(String relativePath, long sizeBytes, long lastModified, List<String> lines) {
}
