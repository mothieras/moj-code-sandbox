package com.moj.codesandbox.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ContainerExecutorIT {

    @Autowired ContainerExecutor executor;
    @Autowired DockerContainerProvider provider;

    private PooledContainer c;

    @BeforeEach void setUp() { c = provider.create(); }
    @AfterEach void tearDown() { provider.destroy(c); }

    @Test
    void stdout_is_captured() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, "echo", "hello");
        assertEquals(0, r.getExitCode());
        assertTrue(r.getStdout().contains("hello"));
        assertFalse(r.isTimeout());
    }

    @Test
    void stderr_is_separated_from_stdout() {
        // 往 stderr 写，stdout 应为空、stderr 有内容（验雷1：不开 TTY 才能分流）
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, "sh", "-c", "echo boom 1>&2");
        assertTrue(r.getStderr().contains("boom"));
        assertFalse(r.getStdout().contains("boom"));
    }

    @Test
    void nonzero_exit_code_is_reported() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 5, "sh", "-c", "exit 7");
        assertEquals(7, r.getExitCode());
    }

    @Test
    void timeout_is_detected_and_marked() {
        ContainerExecutor.ExecResult r = executor.exec(c.getContainerId(), 1, "sleep", "10");
        assertTrue(r.isTimeout());   // 验雷3：超时被检测到
    }
}
