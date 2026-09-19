package com.chat.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * 打包工具：把编译产物打成可执行 jar。
 *
 * <p>存在意义：本项目不依赖 Maven/Gradle，而部分精简 JDK 环境可能未提供 {@code jar} 命令。
 * 使用标准库 {@link JarOutputStream} 自行打包，既保证跨平台一致，也顺便演示了
 * Java 归档格式的操作方式。</p>
 *
 * <p>用法（可传入多个类目录，例如把测试类一起打进测试 jar）。
 * 以 IDEA 的输出目录为例（工作目录为项目根目录）：</p>
 * <pre>
 * java -cp out/production/LANChat:out/test/LANChat com.chat.test.JarPackager \
 *      dist/chat-server.jar com.chat.server.ChatServer out/production/LANChat
 * </pre>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class JarPackager {

    /** 需要在 jar 中排除的临时文件后缀 */
    private static final String[] EXCLUDED_SUFFIXES = {".tmp", ".bak"};

    /** 私有构造，禁止实例化工具类 */
    private JarPackager() {
    }

    /**
     * 打包入口。
     *
     * @param args 依次为：classes 目录、输出 jar 路径、主类全限定名
     * @throws IOException 打包失败时抛出
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: JarPackager <outputJar> <mainClass> <classesDir> [moreClassesDirs...]");
            System.exit(2);
        }
        Path outputJar = Paths.get(args[0]);
        String mainClass = args[1];
        List<Path> roots = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Path root = Paths.get(args[i]);
            if (!Files.isDirectory(root)) {
                throw new IOException("类目录不存在: " + root.toAbsolutePath());
            }
            roots.add(root);
        }
        if (outputJar.getParent() != null) {
            Files.createDirectories(outputJar.getParent());
        }
        writeJar(roots, outputJar, mainClass);
        System.out.println("已生成可执行 jar: " + outputJar.toAbsolutePath() + "（主类 " + mainClass + "）");
    }

    /**
     * 收集目录下全部 class 文件。
     *
     * @param classesDir 类目录
     * @return class 文件路径列表
     * @throws IOException 遍历失败时抛出
     */
    private static List<Path> collectClassFiles(Path classesDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(files::add);
        }
        return files;
    }

    /**
     * 写出 jar 文件。
     *
     * @param roots     一个或多个类目录（每个目录的条目名按其自身计算相对路径）
     * @param outputJar 输出文件
     * @param mainClass 主类
     * @throws IOException 写入失败时抛出
     */
    private static void writeJar(List<Path> roots, Path outputJar, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "局域网聊天程序");
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0");

        int total = 0;
        Set<String> written = new HashSet<>();
        try (OutputStream fileOut = Files.newOutputStream(outputJar);
             JarOutputStream jarOut = new JarOutputStream(fileOut, manifest)) {
            for (Path root : roots) {
                List<Path> classFiles = collectClassFiles(root);
                for (Path classFile : classFiles) {
                    String entryName = root.relativize(classFile).toString().replace('\\', '/');
                    if (isExcluded(entryName) || !written.add(entryName)) {
                        // 重复条目会被 jar 规范拒绝，这里以先出现的目录为准
                        continue;
                    }
                    jarOut.putNextEntry(new JarEntry(entryName));
                    try (InputStream in = Files.newInputStream(classFile)) {
                        in.transferTo(jarOut);
                    }
                    jarOut.closeEntry();
                    total++;
                }
            }
        }
        System.out.println("      写入 " + total + " 个类文件到 " + outputJar.getFileName());
    }

    /**
     * 判断条目是否应被排除。
     *
     * @param entryName 条目名
     * @return 应排除返回 true
     */
    private static boolean isExcluded(String entryName) {
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (entryName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
