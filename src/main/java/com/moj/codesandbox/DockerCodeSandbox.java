package com.moj.codesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Statistics;
import com.moj.codesandbox.config.SandboxProperties;
import com.moj.codesandbox.model.ExecuteCodeRequest;
import com.moj.codesandbox.model.ExecuteCodeResponse;
import com.moj.codesandbox.model.ExecuteMessage;
import com.moj.codesandbox.model.JudgeInfo;
import com.moj.codesandbox.model.LanguageConfig;
import com.moj.codesandbox.pool.ContainerExecutor;
import com.moj.codesandbox.pool.ContainerPoolManager;
import com.moj.codesandbox.pool.PooledContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 语言无关的 Docker 代码沙箱：由 {@link LanguageConfig} 驱动编译/运行命令，
 * 容器池按语言路由复用。所有用户相关命令走 docker exec 的 argv 模式，不经 shell。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String CONTAINER_BOX = "/box";

    private final ContainerPoolManager poolManager;
    private final ContainerExecutor executor;
    private final SandboxProperties props;
    private final DockerClient dockerClient;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        String code = request.getCode();
        List<String> inputList = request.getInputList() != null
                ? request.getInputList()
                : Collections.emptyList();

        LanguageConfig lang = LanguageConfig.of(request.getLanguage());

        if (StrUtil.isBlank(code)) {
            return errorResponse("代码为空");
        }

        File userCodeFile = saveCodeToFile(code, lang.getCodeFileName());

        PooledContainer container;
        try {
            container = poolManager.borrow(request.getLanguage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteFile(userCodeFile);
            return errorResponse("借容器被中断");
        }

        try {
            // 写源码到容器工作目录（绑定到 /box）
            String codeContent = FileUtil.readUtf8String(userCodeFile);
            FileUtil.writeUtf8String(codeContent,
                    container.getHostWorkDir() + File.separator + lang.getCodeFileName());

            String srcFile = CONTAINER_BOX + "/" + lang.getCodeFileName();

            // 编译（若有模板；Python 无编译）
            ExecuteMessage compileResult = null;
            if (lang.getCompileCmdTemplate() != null) {
                String[] compileCmd = parseCmdTemplate(
                        lang.getCompileCmdTemplate(), srcFile, CONTAINER_BOX, null);
                ContainerExecutor.ExecResult compile = executor.exec(
                        container.getContainerId(), props.getTimeoutSeconds(),
                        null, compileCmd);

                compileResult = new ExecuteMessage();
                compileResult.setExitVal((int) compile.getExitCode());
                compileResult.setMessage(compile.getStdout());
                compileResult.setErrorMessage(compile.getStderr() != null ? compile.getStderr() : "");

                if (compile.getExitCode() != 0) {
                    return ExecuteCodeResponse.builder()
                            .compileResult(compileResult)
                            .runResults(Collections.emptyList())
                            .outputList(Collections.emptyList())
                            .message(StrUtil.isBlank(compile.getStderr()) ? "编译失败" : compile.getStderr())
                            .status(3)
                            .judgeInfo(new JudgeInfo())
                            .build();
                }
            }

            // 启动内存采样（必须在运行前开始）
            final long[] maxMem = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(container.getContainerId());
            statsCmd.exec(new ResultCallback.Adapter<Statistics>() {
                @Override
                public void onNext(Statistics s) {
                    Long usage = (s.getMemoryStats() == null) ? null : s.getMemoryStats().getUsage();
                    if (usage != null) maxMem[0] = Math.max(maxMem[0], usage);
                }
            });

            // 逐用例运行
            List<ExecuteMessage> runResults = new ArrayList<>();
            for (String inputArgs : inputList) {
                String[] argsTokens = StrUtil.isBlank(inputArgs)
                        ? new String[0]
                        : inputArgs.trim().split("\\s+");
                String[] runCmd = parseCmdTemplate(
                        lang.getRunCmdTemplate(), srcFile, CONTAINER_BOX, argsTokens);

                ContainerExecutor.ExecResult r = executor.exec(
                        container.getContainerId(), props.getTimeoutSeconds(),
                        lang.getKillTarget(), runCmd);

                ExecuteMessage m = new ExecuteMessage();
                m.setTime(r.getTimeMillis());
                if (r.isTimeout()) {
                    m.setTimeout(true);
                    m.setExitVal(1);
                    m.setErrorMessage("执行超时");
                } else if (r.getExitCode() != 0) {
                    m.setExitVal((int) r.getExitCode());
                    m.setErrorMessage(StrUtil.isBlank(r.getStderr()) ? "运行错误" : r.getStderr());
                } else {
                    m.setExitVal(0);
                    m.setMessage(r.getStdout());
                }
                runResults.add(m);
            }

            // 关闭采样，回填内存峰值
            try { Thread.sleep(200); statsCmd.close(); } catch (Exception ignored) {}
            long maxMemMB = maxMem[0] / (1024 * 1024);
            log.info("本次容器内存峰值 {} MB ({} 字节)", maxMemMB, maxMem[0]);
            for (ExecuteMessage m : runResults) {
                m.setMemory(maxMemMB);
            }

            ExecuteCodeResponse response = getOutputResponse(runResults);
            boolean hasError = runResults.stream().anyMatch(m ->
                    Boolean.TRUE.equals(m.getTimeout())
                            || (m.getExitVal() != null && m.getExitVal() != 0));
            if (hasError) {
                response.setStatus(3);
            }
            response.setCompileResult(compileResult);
            response.setRunResults(runResults);
            return response;

        } finally {
            try {
                executor.exec(container.getContainerId(), 5, null,
                        "sh", "-c", "rm -rf /box/*");
                poolManager.giveBack(request.getLanguage(), container);
            } catch (Exception e) {
                log.warn("清理工作目录失败，销毁容器 containerId={}", container.getContainerId(), e);
                poolManager.replace(request.getLanguage(), container);
            }
            deleteFile(userCodeFile);
        }
    }

    /**
     * 把命令模板解析为 argv（不经 shell，避免注入）。占位符须为独立 token：
     * {srcFile}/{srcDir} 替换为容器内路径；{args} 展开为输入 tokens。
     */
    private String[] parseCmdTemplate(String template, String srcFile, String srcDir, String[] argsTokens) {
        List<String> result = new ArrayList<>();
        for (String token : template.split("\\s+")) {
            if (token.equals("{args}")) {
                if (argsTokens != null) {
                    for (String a : argsTokens) {
                        result.add(a);
                    }
                }
            } else {
                result.add(token.replace("{srcFile}", srcFile).replace("{srcDir}", srcDir));
            }
        }
        return result.toArray(new String[0]);
    }

    private File saveCodeToFile(String code, String codeFileName) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + codeFileName;
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    private ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage m : executeMessageList) {
            outputList.add(m.getMessage());
            Long time = m.getTime();
            Long memory = m.getMemory();
            if (time != null) maxTime = Math.max(maxTime, time);
            if (memory != null) maxMemory = Math.max(maxMemory, memory);
        }
        response.setOutputList(outputList);
        response.setStatus(1);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        response.setJudgeInfo(judgeInfo);
        return response;
    }

    private boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            return FileUtil.del(userCodeFile.getParentFile());
        }
        return true;
    }

    private ExecuteCodeResponse errorResponse(String message) {
        return ExecuteCodeResponse.builder()
                .message(message)
                .status(2)
                .judgeInfo(new JudgeInfo())
                .build();
    }
}
