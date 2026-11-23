package com.chat.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记可被 TestRunner 执行的测试方法，并携带用例标题。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
    /**
     * 用例标题。
     *
     * @return 标题文本
     */
    String value();
}
