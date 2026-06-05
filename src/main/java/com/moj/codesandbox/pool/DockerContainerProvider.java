package com.moj.codesandbox.pool;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.moj.codesandbox.config.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.File;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerContainerProvider implements ContainerProvider {

    private final DockerClient dockerClient;
    private final SandboxProperties props;
    private volatile boolean imageReady = false;

    @Override
    public PooledContainer create() {
        ensureImage();
        String hostWorkDir = props.getWorkRoot() + File.separator + UUID.randomUUID();
        FileUtil.mkdir(hostWorkDir);
        // 让容器内非 root 用户(nobody)可写
        File dir = new File(hostWorkDir);
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);

        HostConfig hostConfig = new HostConfig()
                .withBinds(new Bind(hostWorkDir, new Volume("/box")))
                .withMemory(props.getMemoryLimit())
                .withMemorySwap(props.getMemoryLimit())   // ==memory 才真正禁 swap
                .withCpuCount(props.getCpuCount())
                .withPidsLimit(props.getPidsLimit())      // 防 fork 炸弹
                .withReadonlyRootfs(true);                // 根文件系统只读，仅 /box 可写

        CreateContainerResponse resp = dockerClient.createContainerCmd(props.getImage())
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)                // 禁网
                .withUser("nobody")                       // 非 root
                .withCmd("tail", "-f", "/dev/null")       // 常驻不退出
                .withTty(false)                           // 不开 TTY，保证 exec 能分流 stdout/stderr
                .exec();
        dockerClient.startContainerCmd(resp.getId()).exec();
        log.info("常驻容器已启动 containerId={}", resp.getId());
        return new PooledContainer(resp.getId(), hostWorkDir);
    }

    @Override
    public void destroy(PooledContainer c) {
        try { dockerClient.stopContainerCmd(c.getContainerId()).withTimeout(2).exec(); } catch (Exception ignored) {}
        try { dockerClient.removeContainerCmd(c.getContainerId()).withForce(true).exec(); } catch (Exception ignored) {}
        try { FileUtil.del(c.getHostWorkDir()); } catch (Exception ignored) {}
    }

    @Override
    public boolean isHealthy(PooledContainer c) {
        try {
            Boolean running = dockerClient.inspectContainerCmd(c.getContainerId())
                    .exec().getState().getRunning();
            return Boolean.TRUE.equals(running);
        } catch (Exception e) {
            return false;
        }
    }

    /** 首次创建前确保镜像存在，只拉一次 */
    private void ensureImage() {
        if (imageReady) return;
        synchronized (this) {
            if (imageReady) return;
            String image = props.getImage();
            List<Image> images = dockerClient.listImagesCmd().exec();
            boolean exists = images.stream()
                    .filter(i -> i.getRepoTags() != null)
                    .flatMap(i -> java.util.Arrays.stream(i.getRepoTags()))
                    .anyMatch(image::equals);
            if (!exists) {
                log.info("拉取镜像 {}", image);
                try {
                    dockerClient.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("拉取镜像被中断", e);
                }
            }
            imageReady = true;
        }
    }
}
