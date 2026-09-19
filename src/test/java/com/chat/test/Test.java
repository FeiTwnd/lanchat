package com.chat.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试方法标记注解。
 *
 * <p>职责：标注哪些方法应当被 {@link TestRunner} 作为测试用例执行。
 * 本项目刻意不引入 JUnit 依赖（题目要求零第三方框架、且交付环境可能无外网），
 * 因此定义一个极简注解配合自研运行器，实现同样效果的自动化测试。</p>
 *
 * <p>用法：在测试类的公共无参实例方法上添加 {@code @Test("用例说明")} 即可。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {

    /**
     * 用例说明，用于测试报告输出。
     *
     * @return 用例描述文本
     */
    String value();
}
