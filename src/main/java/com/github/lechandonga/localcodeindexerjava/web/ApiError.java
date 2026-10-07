package com.github.lechandonga.localcodeindexerjava.web;

import java.time.Instant;

/**
 * 统一错误响应体：稳定错误码 + 安全消息，不包含原始异常类型与堆栈。
 */
public record ApiError(String errorCode, String message, String path, Instant timestamp) {
}
