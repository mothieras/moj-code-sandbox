package com.yupi.mojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Statistics;
import com.yupi.mojcodesandbox.config.SandboxProperties;
import com.yupi.mojcodesandbox.model.ExecuteMessage;
import com.yupi.mojcodesandbox.pool.ContainerExecutor;
import com.yupi.mojcodesandbox.pool.ContainerPool;
import com.yupi.mojcodesandbox.pool.PooledContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.File;
import java.util.ArrayList;
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

    /** 编译改到容器内进行（修雷2），这里跳过宿主机编译 */
    @Override
    public ExecuteMessage compileFile(File userCodeFile) {
        ExecuteMessage m = new ExecuteMessage();
        m.setExitVal(0);
        return m;
    }

    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        List<ExecuteMessage> messages = new ArrayList<>();
        PooledContainer container;
        try {
            container = containerPool.borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("借容器被中断", e);
        }

        try {
            // 1. 把源码写进容器专属工作目录
            String code = FileUtil.readUtf8String(userCodeFile);
            FileUtil.writeUtf8String(code, container.getHostWorkDir() + File.separator + "Main.java");

            // 2. 容器内编译
            ContainerExecutor.ExecResult compile = executor.exec(
                    container.getContainerId(), props.getTimeoutSeconds(),
                    "javac", "-encoding", "utf-8", "/box/Main.java");
            if (compile.getExitCode() != 0) {
                ExecuteMessage m = new ExecuteMessage();
                m.setExitVal(1);
                m.setErrorMessage(StrUtil.isBlank(compile.getStderr()) ? "编译失败" : compile.getStderr());
                messages.add(m);
                return messages;
            }

            // 3. 开内存采样（用 getUsage() 采样取峰值，cgroup v2 也可用——修雷5）
            final long[] maxMem = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(container.getContainerId());
            statsCmd.exec(new ResultCallback.Adapter<Statistics>() {
                @Override public void onNext(Statistics s) {
                    Long usage = (s.getMemoryStats() == null) ? null : s.getMemoryStats().getUsage();
                    if (usage != null) maxMem[0] = Math.max(maxMem[0], usage);
                }
            });

            // 4. 逐个输入运行
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
                    m.setExitVal(1);
                    m.setErrorMessage("执行超时");
                } else if (r.getExitCode() != 0) {
                    m.setExitVal((int) r.getExitCode());
                    m.setErrorMessage(StrUtil.isBlank(r.getStderr()) ? "运行错误" : r.getStderr());
                } else {
                    m.setExitVal(0);
                    m.setMessage(r.getStdout());
                }
                messages.add(m);
            }

            try { Thread.sleep(200); statsCmd.close(); } catch (Exception ignored) {}
            log.info("本次容器内存峰值 {} 字节", maxMem[0]);
            for (ExecuteMessage m : messages) m.setMemory(maxMem[0]);
            return messages;

        } finally {
            // 5. 清空工作目录（复用隔离）+ 还容器
            try {
                executor.exec(container.getContainerId(), 5, "sh", "-c", "rm -rf /box/*");
            } catch (Exception e) {
                log.warn("清理工作目录失败 containerId={}", container.getContainerId(), e);
            }
            containerPool.giveBack(container);
        }
    }
}
