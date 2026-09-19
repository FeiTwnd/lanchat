package com.chat.test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轻量测试运行器。
 *
 * <p>职责：反射扫描测试类中标注了 {@link Test} 的方法并逐一执行，
 * 统计通过/失败数量，输出形如测试报告的汇总结果，失败时以非零退出码结束
 * 以便被脚本判断。</p>
 *
 * <p>为什么自研而不引入 JUnit：见 {@link Test} 的说明；自研运行器同时让
 * “测试用例表”的生成变得直接——运行输出即是测试报告的数据来源。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class TestRunner {

    /** 已执行用例数 */
    private static int executed;

    /** 通过用例数 */
    private static int passed;

    /** 失败用例列表 */
    private static final List<String> failures = new ArrayList<>();

    /** 临时目录前缀 */
    private static final String TEMP_PREFIX = "lanchat-test-";

    /** 私有构造，禁止实例化 */
    private TestRunner() {
    }

    /**
     * 主入口：执行全部测试类。
     *
     * @param args 可选的测试类全限定名；不传则执行全部内置测试
     */
    public static void main(String[] args) {
        List<Class<?>> targets = new ArrayList<>();
        if (args.length > 0) {
            for (String className : args) {
                try {
                    targets.add(Class.forName(className));
                } catch (ClassNotFoundException e) {
                    System.out.println("[错误] 找不到测试类: " + className);
                    System.exit(2);
                }
            }
        } else {
            targets.add(SecurityUtilTest.class);
            targets.add(UserDaoTest.class);
            targets.add(UserServiceTest.class);
            targets.add(MessageServiceTest.class);
            targets.add(FileServiceTest.class);
        }
        System.out.println("========== 单元测试开始 ==========");
        for (Class<?> target : targets) {
            runClass(target);
        }
        printSummary();
    }

    /**
     * 执行单个测试类。
     *
     * @param testClass 测试类
     */
    public static void runClass(Class<?> testClass) {
        System.out.println("\n--- " + testClass.getSimpleName() + " ---");
        List<Method> methods = collectTestMethods(testClass);
        if (methods.isEmpty()) {
            System.out.println("  (无测试方法)");
            return;
        }
        for (Method method : methods) {
            execute(testClass, method);
        }
    }

    /**
     * 收集测试类中标注了 {@link Test} 的方法，按方法名字典序排列以保证输出稳定。
     *
     * @param testClass 测试类
     * @return 测试方法列表
     */
    private static List<Method> collectTestMethods(Class<?> testClass) {
        List<Method> methods = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class) && method.getParameterCount() == 0) {
                methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    /**
     * 执行单个测试方法。
     *
     * @param testClass 测试类
     * @param method    测试方法
     */
    private static void execute(Class<?> testClass, Method method) {
        Test annotation = method.getAnnotation(Test.class);
        String title = annotation.value();
        executed++;
        try {
            Object instance = testClass.getDeclaredConstructor().newInstance();
            method.setAccessible(true);
            method.invoke(instance);
            passed++;
            System.out.println("  [通过] " + title + " (" + method.getName() + ")");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            failures.add(testClass.getSimpleName() + "." + method.getName() + " - " + title
                    + " : " + cause.getMessage());
            System.out.println("  [失败] " + title + " (" + method.getName() + ")");
            System.out.println("         原因: " + cause.getMessage());
        } catch (ReflectiveOperationException e) {
            failures.add(testClass.getSimpleName() + "." + method.getName() + " - " + title
                    + " : 反射调用失败");
            System.out.println("  [异常] " + title + " : " + e.getMessage());
        }
    }

    /**
     * 输出测试汇总。
     */
    private static void printSummary() {
        System.out.println("\n========== 测试汇总 ==========");
        System.out.println("执行用例: " + executed);
        System.out.println("通过: " + passed);
        System.out.println("失败: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("失败明细:");
            failures.forEach(item -> System.out.println("  - " + item));
        }
        System.out.println("结论: " + (failures.isEmpty() ? "全部通过" : "存在失败用例"));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * 创建测试专用临时目录。
     *
     * @param name 目录名后缀
     * @return 临时目录路径
     * @throws IOException 创建失败时抛出
     */
    public static Path createTempDir(String name) throws IOException {
        return Files.createTempDirectory(TEMP_PREFIX + name + "-");
    }

    /**
     * 递归删除测试产生的临时目录。
     *
     * @param path 目录路径
     */
    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            System.out.println("[警告] 临时目录清理失败: " + e.getMessage());
        }
    }

    /**
     * 断言条件为真。
     *
     * @param condition 条件
     * @param message   失败提示
     */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 断言条件为假。
     *
     * @param condition 条件
     * @param message   失败提示
     */
    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    /**
     * 断言对象不为空。
     *
     * @param value   待检查对象
     * @param message 失败提示
     */
    public static void assertNotNull(Object value, String message) {
        assertTrue(value != null, message);
    }

    /**
     * 断言对象为空。
     *
     * @param value   待检查对象
     * @param message 失败提示
     */
    public static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    /**
     * 断言两个对象相等。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @param message  失败提示
     */
    public static void assertEquals(Object expected, Object actual, String message) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 断言两个对象不相等。
     *
     * @param unexpected 不期望的值
     * @param actual     实际值
     * @param message    失败提示
     */
    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        boolean equal = unexpected == null ? actual == null : unexpected.equals(actual);
        if (equal) {
            throw new AssertionError(message + "：不应等于 " + unexpected);
        }
    }

    /**
     * 断言字节数组内容一致，失败时输出长度差异便于定位。
     *
     * @param expected 期望字节
     * @param actual   实际字节
     * @param message  失败提示
     */
    public static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        assertNotNull(actual, message + "：实际值为 null");
        assertTrue(expected.length == actual.length,
                message + "：长度不一致，期望 " + expected.length + "，实际 " + actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError(message + "：第 " + i + " 字节不一致");
            }
        }
    }
}
