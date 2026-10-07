package com.github.lechandonga.localcodeindexerjava.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 本地代码索引器配置项，前缀 {@code local-indexer}。
 */
@ConfigurationProperties(prefix = "local-indexer")
public class IndexerProperties {

    /** 要扫描建索引的本地代码目录，必须存在且是目录。 */
    private String rootPath = ".";

    /** 单文件大小上限（字节），超过则跳过，原因 too_large。 */
    private long maxFileSizeBytes = 5L * 1024 * 1024;

    /** 命中行返回片段时，关键字两侧保留的最大字符数。 */
    private int snippetContextChars = 40;

    /** 单次查询返回的最大命中数。 */
    private int maxResults = 200;

    /** 扩展名白名单（小写、不含点，例如 java,xml）；为空表示不过滤，二进制检测仍然生效。 */
    private List<String> includeExtensions = List.of();

    /**
     * 并发触发刷新时的策略：
     * reject = 拒绝后来的请求；join = 复用进行中的刷新并等待同一份结果。
     */
    private RefreshConflictPolicy refreshConflictPolicy = RefreshConflictPolicy.reject;

    public enum RefreshConflictPolicy { reject, join }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getSnippetContextChars() {
        return snippetContextChars;
    }

    public void setSnippetContextChars(int snippetContextChars) {
        this.snippetContextChars = snippetContextChars;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public List<String> getIncludeExtensions() {
        return includeExtensions;
    }

    public void setIncludeExtensions(List<String> includeExtensions) {
        this.includeExtensions = includeExtensions;
    }

    public RefreshConflictPolicy getRefreshConflictPolicy() {
        return refreshConflictPolicy;
    }

    public void setRefreshConflictPolicy(RefreshConflictPolicy refreshConflictPolicy) {
        this.refreshConflictPolicy = refreshConflictPolicy;
    }
}
