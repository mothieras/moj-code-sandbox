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

    /**
     * 在容器内执行命令（argv 模式，不经 shell，避免注入）。
     *
     * @param killTarget 超时后要 pkill 的用户进程名（来自 LanguageConfig）；null 则超时不主动 kill
     */
    public ExecResult exec(String containerId, long timeoutSeconds, String killTarget, String... cmd) {
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
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
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
            if (killTarget != null) {
                killRunaway(containerId, killTarget);          // 按语言进程名杀残留
            }
        } else {
            Long code = dockerClient.inspectExecCmd(created.getId()).exec().getExitCodeLong();
            result.setExitCode(code == null ? -1 : code);
        }
        return result;
    }

    /** 超时后按进程名杀掉容器内残留的用户进程，保证复用前干净 */
    public void killRunaway(String containerId, String killTarget) {
        try {
            ExecCreateCmdResponse kill = dockerClient.execCreateCmd(containerId)
                    .withCmd("pkill", "-9", killTarget).exec();
            dockerClient.execStartCmd(kill.getId())
                    .exec(new ExecStartResultCallback())
                    .awaitCompletion(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("killRunaway 失败 containerId={} target={}", containerId, killTarget, e);
        }
    }
}
