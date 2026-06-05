package com.moj.codesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
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
import com.moj.codesandbox.pool.ContainerExecutor;
import com.moj.codesandbox.pool.ContainerPool;
import com.moj.codesandbox.pool.PooledContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Java 代码沙箱：容器池复用实现 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    private final ContainerPool containerPool;
    private final ContainerExecutor executor;
    private final SandboxProperties props;
    private final DockerClient dockerClient;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInputList() != null
                ? executeCodeRequest.getInputList()
                : Collections.emptyList();

        if (StrUtil.isBlank(code)) {
            return ExecuteCodeResponse.builder()
                    .message("代码为空")
                    .status(2)
                    .judgeInfo(new JudgeInfo())
                    .build();
        }

        // 1. 保存代码到宿主机
        File userCodeFile = saveCodeToFile(code);

        // 2. 借容器
        PooledContainer container;
        try {
            container = containerPool.borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteFile(userCodeFile);
            return ExecuteCodeResponse.builder()
                    .compileResult(null)
                    .runResults(Collections.emptyList())
                    .outputList(Collections.emptyList())
                    .message("借容器被中断")
                    .status(2)
                    .judgeInfo(new JudgeInfo())
                    .build();
        }

        try {
            // 3. 写源码到容器
            String codeContent = FileUtil.readUtf8String(userCodeFile);
            FileUtil.writeUtf8String(codeContent,
                    container.getHostWorkDir() + File.separator + "Main.java");

            // 4. 容器内编译
            ContainerExecutor.ExecResult compile = executor.exec(
                    container.getContainerId(), props.getTimeoutSeconds(),
                    "javac", "-encoding", "utf-8", "/box/Main.java");

            ExecuteMessage compileResult = new ExecuteMessage();
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

            // 5. 启动内存采样（必须在运行前开始）
            final long[] maxMem = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(container.getContainerId());
            statsCmd.exec(new ResultCallback.Adapter<Statistics>() {
                @Override
                public void onNext(Statistics s) {
                    Long usage = (s.getMemoryStats() == null) ? null : s.getMemoryStats().getUsage();
                    if (usage != null) maxMem[0] = Math.max(maxMem[0], usage);
                }
            });

            // 6. 逐用例执行
            List<ExecuteMessage> runResults = new ArrayList<>();
            for (String inputArgs : inputList) {
                String[] base = {"java", "-Djava.io.tmpdir=/box", "-cp", "/box", "Main"};
                String[] cmd = StrUtil.isBlank(inputArgs)
                        ? base
                        : ArrayUtil.append(base, inputArgs.trim().split("\\s+"));

                ContainerExecutor.ExecResult r = executor.exec(
                        container.getContainerId(), props.getTimeoutSeconds(), cmd);

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

            // 7. 关闭内存采样，回填 memory
            try { Thread.sleep(200); statsCmd.close(); } catch (Exception ignored) {}
            long maxMemMB = maxMem[0] / (1024 * 1024);
            log.info("本次容器内存峰值 {} MB ({} 字节)", maxMemMB, maxMem[0]);
            for (ExecuteMessage m : runResults) {
                m.setMemory(maxMemMB);
            }

            // 8. 收集输出
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
            // 9. 清空工作目录 + 还容器 + 清理宿主机文件
            try {
                executor.exec(container.getContainerId(), 5, "sh", "-c", "rm -rf /box/*");
                containerPool.giveBack(container);
            } catch (Exception e) {
                log.warn("清理工作目录失败，销毁容器 containerId={}", container.getContainerId(), e);
                containerPool.replace(container);
            }
            deleteFile(userCodeFile);
        }
    }
}
