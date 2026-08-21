package com.moj.codesandbox.pool;

import com.github.dockerjava.api.DockerClient;
import com.moj.codesandbox.config.SandboxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ContainerExecutorIT {

    @Autowired ContainerExecutor executor;
    @Autowired DockerClient dockerClient;
    @Autowired SandboxProperties props;

    private DockerContainerProvider provider;
    private PooledContainer c;

    @BeforeEach
    void setUp() {
        provider = new DockerContainerProvider(dockerClient, props, "amazoncorretto:17-alpine");
        c = provider.create();
    }

    @AfterEach
    void tearDown() {
        provider.destroy(c);
    }

    @Test
    void stdout_is_captured() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, null, "echo", "hello");
        assertEquals(0, r.getExitCode());
        assertTrue(r.getStdout().contains("hello"));
        assertFalse(r.isTimeout());
    }

    @Test
    void stderr_is_separated_from_stdout() {
        // 往 stderr 写，stdout 应为空、stderr 有内容（不开 TTY 才能分流）
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, null, "sh", "-c", "echo boom 1>&2");
        assertTrue(r.getStderr().contains("boom"));
        assertFalse(r.getStdout().contains("boom"));
    }

    @Test
    void nonzero_exit_code_is_reported() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, null, "sh", "-c", "exit 7");
        assertEquals(7, r.getExitCode());
    }

    @Test
    void timeout_is_detected_and_marked() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 1, "sleep", "sleep", "10");
        assertTrue(r.isTimeout());
    }

    @Test
    void timeout_kills_runaway_and_container_remains_reusable() {
        // sleep 30 带 1s 超时 + killTarget=sleep → exec 超时自动 pkill -9 sleep
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 1, "sleep", "sleep", "30");
        assertTrue(r.isTimeout());
        // 容器仍可用：下一条命令正常执行（证明残留进程被清理）
        ContainerExecutor.ExecResult ok = executor.exec(c.getContainerId(), 3, null, "echo", "alive");
        assertEquals(0, ok.getExitCode());
        assertTrue(ok.getStdout().contains("alive"));
    }
}
