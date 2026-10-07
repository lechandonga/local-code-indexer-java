package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试基类：为每个用例打印所属业务场景与执行结果，
 * 使单测日志能直接看出覆盖了哪些业务、每个用例是否通过。
 */
public abstract class BaseIndexerTest {

    protected final Logger testLog = LoggerFactory.getLogger(getClass());

    private long startedAt;

    @BeforeEach
    void logStart(TestInfo info) {
        startedAt = System.currentTimeMillis();
        testLog.info(">>> 用例开始 [{}] {}", businessGroup(), info.getDisplayName());
    }

    @AfterEach
    void logEnd(TestInfo info) {
        testLog.info("<<< 用例通过 [{}] {} ({}ms)", businessGroup(), info.getDisplayName(),
                System.currentTimeMillis() - startedAt);
    }

    /** 子类声明该测试类覆盖的业务场景名称。 */
    protected abstract String businessGroup();
}
