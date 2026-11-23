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
 * 把编译输出目录打包成带主类清单的可执行 jar。
 */
public final class JarPackager {
    private static final String[] EXCLUDED_SUFFIXES = {".tmp", ".bak"};

    private JarPackager() {
    }

    /**
     * 按命令行参数生成可执行 jar。
     *
     * @param args 输出 jar、主类名与一个或多个类目录
     * @throws IOException 读写失败
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

    /** 递归收集目录下的 class 文件。 */
    private static List<Path> collectClassFiles(Path classesDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(files::add);
        }
        return files;
    }

    /** 写入清单与全部类文件。 */
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
                    // 跳过排除项与重复条目
                    if (isExcluded(entryName) || !written.add(entryName)) {
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

    private static boolean isExcluded(String entryName) {
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (entryName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
