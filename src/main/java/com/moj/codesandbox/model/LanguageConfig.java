package com.moj.codesandbox.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持的语言及其执行配置：镜像、源码文件名、编译/运行命令模板、超时 kill 目标。
 * 命令模板占位符（容器内路径）：
 *   {srcFile} → /box/<codeFileName>
 *   {srcDir}  → /box
 *   {args}    → 输入参数 tokens（由调用方按空白拆分）
 */
@Getter
public enum LanguageConfig {
    JAVA(
            "java",
            "amazoncorretto:17-alpine",
            "Main.java",
            "javac -encoding utf-8 {srcFile}",
            "java -Xmx256m -Dfile.encoding=UTF-8 -cp {srcDir} Main {args}",
            "java"
    ),
    CPP(
            "cpp",
            "gcc:13-bookworm",
            "Main.cpp",
            "g++ -o {srcDir}/main {srcFile}",
            "{srcDir}/main {args}",
            "main"
    ),
    PYTHON(
            "python",
            "python:3.12-alpine",
            "code.py",
            null,
            "python3 {srcFile} {args}",
            "python3"
    );

    private final String languageName;
    private final String image;
    private final String codeFileName;
    private final String compileCmdTemplate;
    private final String runCmdTemplate;
    private final String killTarget;

    LanguageConfig(String languageName, String image, String codeFileName,
                   String compileCmdTemplate, String runCmdTemplate, String killTarget) {
        this.languageName = languageName;
        this.image = image;
        this.codeFileName = codeFileName;
        this.compileCmdTemplate = compileCmdTemplate;
        this.runCmdTemplate = runCmdTemplate;
        this.killTarget = killTarget;
    }

    public static LanguageConfig of(String language) {
        if (language == null) {
            throw new IllegalArgumentException("Unsupported language: null");
        }
        switch (language.toLowerCase()) {
            case "java":
                return JAVA;
            case "cpp":
            case "c++":
                return CPP;
            case "python":
            case "python3":
                return PYTHON;
            default:
                throw new IllegalArgumentException("Unsupported language: " + language);
        }
    }

    public static Set<String> supportedLanguageNames() {
        return Arrays.stream(values())
                .map(LanguageConfig::getLanguageName)
                .collect(Collectors.toSet());
    }
}
