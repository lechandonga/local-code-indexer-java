package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试日志扩展：让测试日志清楚地展示覆盖了哪些业务域、每个用例开始与结果。
 * 输出形如：
 * <pre>
 * [TEST-SUITE] ==> RefreshServiceTests （索引刷新：增量/删除/单飞）
 * [TEST-CASE ] ----> concurrent_refresh_requests_are_single_flight
 * [TEST-CASE ]  <== PASSED  concurrent_refresh_requests_are_single_flight (12 ms)
 * [TEST-SUITE] <== RefreshServiceTests: 6 passed, 0 failed
 * </pre>
 */
public class TestLoggingExtension implements BeforeAllCallback, AfterAllCallback,
        BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger("TEST-COVERAGE");

    private final Map<String, AtomicInteger> passed = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failed = new ConcurrentHashMap<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        String suite = suiteName(context);
        log.info("[TEST-SUITE] ==> {} （{}）", suite, businessDomain(context));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String suite = suiteName(context);
        log.info("[TEST-SUITE] <== {}: {} passed, {} failed", suite,
                passed.getOrDefault(suite, new AtomicInteger()).get(),
                failed.getOrDefault(suite, new AtomicInteger()).get());
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        String key = uniqueKey(context);
        startedAt.put(key, System.currentTimeMillis());
        log.info("[TEST-CASE ] ----> {}", caseName(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String suite = suiteName(context);
        boolean success = context.getExecutionException().isEmpty();
        (success ? passed.computeIfAbsent(suite, k -> new AtomicInteger())
                : failed.computeIfAbsent(suite, k -> new AtomicInteger())).incrementAndGet();
        long elapsed = System.currentTimeMillis() - startedAt.getOrDefault(uniqueKey(context), 0L);
        if (success) {
            log.info("[TEST-CASE ]  <== PASSED  {} ({} ms)", caseName(context), elapsed);
        } else {
            log.error("[TEST-CASE ]  <== FAILED  {} ({} ms): {}", caseName(context), elapsed,
                    context.getExecutionException().get().toString());
        }
    }

    private static String suiteName(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }

    private static String caseName(ExtensionContext context) {
        return context.getRequiredTestMethod().getName();
    }

    private static String uniqueKey(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }

    private static String businessDomain(ExtensionContext context) {
        BusinessDomain annotation = context.getRequiredTestClass().getAnnotation(BusinessDomain.class);
        return annotation == null ? "未标注业务域" : annotation.value();
    }
}
