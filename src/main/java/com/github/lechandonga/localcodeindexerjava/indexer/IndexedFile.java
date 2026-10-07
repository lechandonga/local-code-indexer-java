package com.github.lechandonga.localcodeindexerjava.indexer;

import java.util.List;

/**
 * 单个已索引文件的不可变内容：相对路径、内容指纹与按行拆分的文本。
 */
public record IndexedFile(String path, String contentHash, long sizeBytes, long lastModified, List<String> lines) {
}
