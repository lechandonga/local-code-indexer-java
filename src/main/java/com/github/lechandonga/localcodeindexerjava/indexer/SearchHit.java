package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 一条检索命中。
 *
 * @param file       相对根目录的文件路径
 * @param lineNumber 从 1 开始的行号
 * @param snippet    命中位置周围的固定长度片段
 * @param matchType  keyword（普通内容匹配）或 symbol（符号匹配）
 * @param rankScore  用于解释排序的分值，越大越靠前
 */
public record SearchHit(String file, int lineNumber, String snippet, String matchType, int rankScore) {
}
