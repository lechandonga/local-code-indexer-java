package com.github.lechandonga.localcodeindexerjava.index;

/**
 * 单个文件被跳过的稳定原因码，可直接对外暴露。
 */
public enum SkipReason {

    /** 不是普通文件（例如设备文件、管道、指向目录的链接等）。 */
    NOT_REGULAR_FILE,

    /** 文件大小超过配置的上限。 */
    FILE_TOO_LARGE,

    /** 文件内容疑似二进制或无法按 UTF-8 解码。 */
    BINARY_OR_UNDECODABLE_FILE,

    /** 文件读取失败，例如 IO 错误、权限不足、扫描过程中被删除。 */
    UNREADABLE_FILE,

    /** 符号链接解析后指向索引根目录之外，或是已损坏的链接。 */
    LINK_OUTSIDE_ROOT
}
