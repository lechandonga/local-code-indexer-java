package com.github.lechandonga.localcodeindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地代码索引服务的可配置项，前缀 {@code code-indexer}。
 */
@ConfigurationProperties(prefix = "code-indexer")
public class IndexerProperties {

    /** 要索引的本地代码目录绝对路径；为空表示服务不启用建索引。 */
    private String root = "";

    /** 启动时是否自动执行一次刷新。 */
    private boolean autoRefreshOnStartup = true;

    /** 单个文件允许读取的最大字节数，超过则跳过。 */
    private long maxFileBytes = 1024L * 1024L;

    /** 用于判断二进制文件而预读的字节数。 */
    private int binaryProbeBytes = 8192;

    /** 命中片段中命中点两侧保留的字符数。 */
    private int fragmentWindowChars = 60;

    /** 查询默认返回的最大命中条数。 */
    private int defaultLimit = 200;

    /** 查询允许的最大命中条数。 */
    private int maxLimit = 1000;

    /** 递归扫描时按目录名忽略的目录。 */
    private String[] excludeDirNames = {".git", "target", "build", "node_modules", ".idea", ".gradle"};

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public boolean isAutoRefreshOnStartup() {
        return autoRefreshOnStartup;
    }

    public void setAutoRefreshOnStartup(boolean autoRefreshOnStartup) {
        this.autoRefreshOnStartup = autoRefreshOnStartup;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getBinaryProbeBytes() {
        return binaryProbeBytes;
    }

    public void setBinaryProbeBytes(int binaryProbeBytes) {
        this.binaryProbeBytes = binaryProbeBytes;
    }

    public int getFragmentWindowChars() {
        return fragmentWindowChars;
    }

    public void setFragmentWindowChars(int fragmentWindowChars) {
        this.fragmentWindowChars = fragmentWindowChars;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public String[] getExcludeDirNames() {
        return excludeDirNames;
    }

    public void setExcludeDirNames(String[] excludeDirNames) {
        this.excludeDirNames = excludeDirNames;
    }
}
