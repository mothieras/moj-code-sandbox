package com.moj.codesandbox.model;

import lombok.Getter;

@Getter
public enum LanguageConfig {
    JAVA(
            "java",
            ".java",
            "javac -encoding utf-8 {srcFile}", // 编译命令模板
            "java -Xmx256m -Dfile.encoding=UTF-8 -cp {srcDir} Main {args}"
    ),
    CPP(
            "cpp",
            ".cpp",
            "g++ -o {srcDir}/main {srcFile}",
            "{srcDir}/main {args}"
    ),
    PYTHON(
            "python",
            ".py",
            null,  // Python 无需编译
            "python3 {srcFile} {args}"
    );

    private final String languageName;
    private final String fileSuffix;
    private final String compileCmdTemplate;  // 编译命令
    private final String runCmdTemplate;      // 运行命令

    LanguageConfig(String languageName, String fileSuffix,
                   String compileCmdTemplate, String runCmdTemplate) {
        this.languageName = languageName;
        this.fileSuffix = fileSuffix;
        this.compileCmdTemplate = compileCmdTemplate;
        this.runCmdTemplate = runCmdTemplate;
    }

    public static LanguageConfig of(String language) {
        switch (language.toLowerCase()) {
            case "java":
                return JAVA;
            case "cpp":
                return CPP;
            case "python":
                return PYTHON;
            default:
                throw new IllegalArgumentException("Unsupported language: " + language);
        }
    }

}