package com.yupi.mojcodesandbox;

import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yupi.mojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.mojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.mojcodesandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * python语言代码沙箱实现
 */
@Slf4j
@Component
@Deprecated
public class PythonDockerCodeSandbox extends JavaCodeSandboxTemplate {

    /**
     * 每条命令最大超时时间（秒），可根据需要调大或调小
     */
    private static final long TIME_OUT = 8L;

    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();

        // 1. 创建 DockerClient
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        // 2. 选择 Python 镜像
        String image = "python:3.9-alpine";

        // 3. 检查并拉取镜像
        if (!isImageExists(dockerClient, image)) {
            pullImage(dockerClient, image);
        }

        // 4. 创建容器并启动
        String containerId = createAndStartContainer(dockerClient, image, userCodeFile);

        // 5. 启动 Stats 流，持续收集容器的内存峰值
        final long[] maxMemoryUsed = {0L};
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        ResultCallback<Statistics> statsCallback = new ResultCallback<Statistics>() {
            @Override
            public void onNext(Statistics statistics) {
                Long usage = statistics.getMemoryStats().getUsage();
                Long maxUsage = statistics.getMemoryStats().getMaxUsage();
                long cur = usage == null ? 0 : usage;
                long peak = maxUsage == null ? cur : maxUsage;
                maxMemoryUsed[0] = Math.max(maxMemoryUsed[0], peak);
            }

            @Override
            public void onStart(Closeable closeable) {
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("StatsCmd error: ", throwable);
            }

            @Override
            public void onComplete() {
                log.info("StatsCmd onComplete");
            }

            @Override
            public void close() throws IOException {
            }
        };
        statsCmd.exec(statsCallback);

        // 6. 在容器里循环执行多条命令
        for (String inputArgs : inputList) {
            // 命令格式：python3 /app/main.py [arg1 arg2 ...]
            String[] args = inputArgs.trim().split("\\s+");
            // cmdArray: {"python3", "/app/main.py", arg1, arg2, ...}
            String[] cmdArray = ArrayUtil.append(
                    new String[]{"python3", "/app/main.py"},
                    args
            );

            // 创建可执行命令
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .withAttachStdin(true)
                    .exec();

            // 执行并收集输出
            ExecuteMessage execMsg = runCommandAndCollectOutput(dockerClient, execCreateCmdResponse.getId());
            executeMessageList.add(execMsg);
        }

        // 7. 等待一小段时间，让最后一次统计信息被推送
        try {
            Thread.sleep(300);
            statsCmd.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 8. 停止并删除容器
        try {
            dockerClient.stopContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Stop container error:", e);
        }
        try {
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Remove container error:", e);
        }

        // 9. 将 maxMemoryUsed[0] 设置到每条 ExecuteMessage
        log.info("本次容器的最大内存使用：{} 字节", maxMemoryUsed[0]);
        for (ExecuteMessage message : executeMessageList) {
            message.setMemory(maxMemoryUsed[0]);
        }

        return executeMessageList;
    }

    /**
     * 创建并启动 Python 容器
     */
    private String createAndStartContainer(DockerClient dockerClient, String image, File userCodeFile) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        // 限制内存 256MB
        HostConfig hostConfig = new HostConfig()
                .withBinds(new Bind(userCodeParentPath, new Volume("/app")))
                .withMemory(256L * 1024 * 1024)
                .withMemorySwap(0L)
                .withCpuCount(1L)
                .withReadonlyRootfs(true);

        // 创建容器命令
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withAttachStdin(true)
                .withNetworkDisabled(true)
                .withTty(true);

        CreateContainerResponse exec = containerCmd.exec();
        String containerId = exec.getId();
        log.info("创建 Python 容器成功，containerId = {}", containerId);

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Python 容器已启动");
        return containerId;
    }

    /**
     * 执行命令并收集输出、计时信息
     */
    private ExecuteMessage runCommandAndCollectOutput(DockerClient dockerClient, String execId) {
        StopWatch stopWatch = new StopWatch();
        final StringBuilder outBuilder = new StringBuilder();
        final StringBuilder errBuilder = new StringBuilder();

        ExecStartResultCallback callback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                StreamType type = frame.getStreamType();
                String payload = new String(frame.getPayload());
                if (StreamType.STDERR.equals(type)) {
                    errBuilder.append(payload);
                } else {
                    outBuilder.append(payload);
                }
                super.onNext(frame);
            }
        };

        ExecuteMessage execMsg = new ExecuteMessage();
        stopWatch.start();
        try {
            dockerClient.execStartCmd(execId)
                    .exec(callback)
                    .awaitCompletion(TIME_OUT, TimeUnit.SECONDS);

            stopWatch.stop();
            execMsg.setTime(stopWatch.getLastTaskTimeMillis());
            execMsg.setMessage(outBuilder.toString());
            execMsg.setErrorMessage(errBuilder.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execMsg.setMessage("Execution interrupted");
            log.error("execStartCmd interrupted", e);
        } catch (Exception e) {
            execMsg.setMessage("Execution error");
            log.error("execStartCmd error", e);
        }
        return execMsg;
    }

    /**
     * 判断镜像是否存在
     */
    private boolean isImageExists(DockerClient dockerClient, String imageName) {
        try {
            List<Image> images = dockerClient.listImagesCmd().exec();
            for (Image image : images) {
                if (image.getRepoTags() != null) {
                    for (String repoTag : image.getRepoTags()) {
                        if (repoTag.equals(imageName)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (DockerClientException e) {
            throw new RuntimeException("检查镜像存在性失败", e);
        }
    }

    /**
     * 拉取镜像
     */
    private void pullImage(DockerClient dockerClient, String image) {
        log.info("镜像不存在，开始拉取: {}", image);
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
        PullImageResultCallback callback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                log.info("拉取进度: {}", item.getStatus());
                super.onNext(item);
            }
        };
        try {
            pullImageCmd.exec(callback).awaitCompletion();
            log.info("镜像拉取完成: {}", image);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("镜像拉取失败");
            throw new RuntimeException("镜像拉取被中断", e);
        }
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("--------Python 代码沙箱开始执行----------");
        ExecuteCodeResponse executeCodeResponse = super.executeCode(executeCodeRequest);
        log.info("--------Python 代码沙箱执行结束----------");
        return executeCodeResponse;
    }
}