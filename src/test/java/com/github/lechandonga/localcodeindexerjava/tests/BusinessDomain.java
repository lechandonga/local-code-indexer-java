package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注测试类覆盖的业务域，用于测试日志中的覆盖率说明。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("business-domain")
public @interface BusinessDomain {
    String value();
}
