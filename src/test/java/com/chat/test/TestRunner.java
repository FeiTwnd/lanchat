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
 * 反射执行带 Test 注解的方法，统计通过、跳过与失败，并提供断言与临时目录工具。
 */
public final class TestRunner {
    private static int executed;

    private static int passed;

    private static int skipped;

    private static final List<String> failures = new ArrayList<>();

    private static final String TEMP_PREFIX = "lanchat-test-";

    private TestRunner() {
    }

    /**
     * 抛出跳过标记，结束当前用例。
     *
     * @param reason 跳过原因
     */
    public static void skip(String reason) {
        throw new SkippedCaseException(reason);
    }

    /**
     * 用于中断当前用例并标记为跳过。
     */
    public static final class SkippedCaseException extends RuntimeException {
        private static final long serialVersionUID = 20260919L;

        SkippedCaseException(String reason) {
            super(reason);
        }
    }

    /**
     * 执行指定测试类，未指定时运行内置的单元测试清单。
     *
     * @param args 测试类全限定名列表
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
            targets.add(ProtocolTest.class);
        }
        System.out.println("========== 单元测试开始 ==========");
        for (Class<?> target : targets) {
            runClass(target);
        }
        printSummary();
    }

    /**
     * 执行一个测试类中的全部测试方法。
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

    /** 调用单个测试方法并归类执行结果。 */
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
            if (cause instanceof SkippedCaseException) {
                skipped++;
                System.out.println("  [跳过] " + title + " (" + method.getName() + ")");
                System.out.println("         原因: " + cause.getMessage());
                return;
            }
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

    private static void printSummary() {
        System.out.println("\n========== 测试汇总 ==========");
        System.out.println("执行用例: " + executed);
        System.out.println("通过: " + passed);
        System.out.println("跳过: " + skipped);
        System.out.println("失败: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("失败明细:");
            failures.forEach(item -> System.out.println("  - " + item));
        }
        String conclusion = failures.isEmpty()
                ? (skipped > 0 ? "全部通过（" + skipped + " 条因环境不满足被跳过）" : "全部通过")
                : "存在失败用例";
        System.out.println("结论: " + conclusion);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * 创建带指定名称片段的临时目录。
     *
     * @param name 名称片段
     * @return 临时目录路径
     * @throws IOException 创建失败
     */
    public static Path createTempDir(String name) throws IOException {
        return Files.createTempDirectory(TEMP_PREFIX + name + "-");
    }

    /**
     * 递归删除目录及其内容，路径不存在时直接返回。
     *
     * @param path 待删除路径
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
     * @param condition 待检查条件
     * @param message 失败提示
     */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 断言条件为假。
     *
     * @param condition 待检查条件
     * @param message 失败提示
     */
    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    /**
     * 断言对象不为 null。
     *
     * @param value 待检查对象
     * @param message 失败提示
     */
    public static void assertNotNull(Object value, String message) {
        assertTrue(value != null, message);
    }

    /**
     * 断言对象为 null。
     *
     * @param value 待检查对象
     * @param message 失败提示
     */
    public static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    /**
     * 断言两个对象相等。
     *
     * @param expected 期望值
     * @param actual 实际值
     * @param message 失败提示
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
     * @param unexpected 不应出现的值
     * @param actual 实际值
     * @param message 失败提示
     */
    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        boolean equal = unexpected == null ? actual == null : unexpected.equals(actual);
        if (equal) {
            throw new AssertionError(message + "：不应等于 " + unexpected);
        }
    }

    /**
     * 断言两个字节数组长度与内容一致。
     *
     * @param expected 期望数组
     * @param actual 实际数组
     * @param message 失败提示
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
