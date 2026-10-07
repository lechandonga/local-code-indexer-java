package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 单个文件在建索引时被跳过的稳定、可区分原因。
 * 对外接口直接暴露 {@link #code()}，不随底层实现变化。
 */
public enum SkipReason {

    /** 文件位于配置的根目录之外（例如指向目录外的符号链接）。 */
    OUTSIDE_ROOT("outside_root", "文件位于索引根目录之外"),

    /** 符号链接形成环或无法安全解析。 */
    SYMLINK_LOOP("symlink_loop", "符号链接形成环"),

    /** 文件大小超过配置的上限。 */
    TOO_LARGE("too_large", "文件超过大小上限"),

    /** 内容被判定为二进制文件。 */
    BINARY("binary", "二进制文件"),

    /** 文件不是普通文件（设备、套接字等）。 */
    NOT_REGULAR_FILE("not_regular_file", "不是普通文件"),

    /** 文件无法读取（权限不足或读取时发生 I/O 异常）。 */
    UNREADABLE("unreadable", "文件不可读或读取失败"),

    /** 不是受支持的代码/文本文件扩展名（启用扩展名过滤时）。 */
    IGNORED_EXTENSION("ignored_extension", "扩展名不在索引范围内");

    private final String code;
    private final String description;

    SkipReason(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
