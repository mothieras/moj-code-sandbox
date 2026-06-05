package com.moj.codesandbox.pool;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 在指定容器内执行命令：不开 TTY、分流 stdout/stderr、硬超时 kill、取退出码 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerExecutor {

    private final DockerClient dockerClient;

    @Data
    public static class ExecResult {
        private String stdout = "";
        private String stderr = "";
        private long exitCode = -1;
        private long timeMillis;
        private boolean timeout;
    }

    public ExecResult exec(String containerId, long timeoutSeconds, String... cmd) {
        ExecResult result = new ExecResult();
        ExecCreateCmdResponse created = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecStartResultCallback callback = new ExecStartResultCallback() {
            @Override public void onNext(Frame frame) {
                try {
                    if (StreamType.STDERR.equals(frame.getStreamType())) {
                        err.write(frame.getPayload());
                    } else {
                        out.write(frame.getPayload());
                    }
                } catch (Exception ignored) {}
            }
        };

        StopWatch sw = new StopWatch();
        sw.start();
        boolean completed;
        try {
            completed = dockerClient.execStartCmd(created.getId())
                    .exec(callback)
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);   // 接住返回值（修雷3）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        sw.stop();

        result.setTimeMillis(sw.getLastTaskTimeMillis());
        result.setStdout(new String(out.toByteArray(), StandardCharsets.UTF_8));
        result.setStderr(new String(err.toByteArray(), StandardCharsets.UTF_8));

        if (!completed) {
            result.setTimeout(true);
            killRunaway(containerId);                                     // 超时杀残留进程
        } else {
            Long code = dockerClient.inspectExecCmd(created.getId()).exec().getExitCodeLong();
            result.setExitCode(code == null ? -1 : code);
        }
        return result;
    }

    /** 超时后杀掉容器内残留的用户进程，保证复用前干净 */
    private void killRunaway(String containerId) {
        try {
            ExecCreateCmdResponse kill = dockerClient.execCreateCmd(containerId)
                    .withCmd("pkill", "-9", "java").exec();               // busybox 自带 pkill
            dockerClient.execStartCmd(kill.getId())
                    .exec(new ExecStartResultCallback())
                    .awaitCompletion(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("killRunaway 失败 containerId={}", containerId, e);
        }
    }
}
