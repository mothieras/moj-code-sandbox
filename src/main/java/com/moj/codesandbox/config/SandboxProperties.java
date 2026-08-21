package com.moj.codesandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {
    /** 容器池大小 */
    private int poolSize = 5;
    /** 单次执行硬超时（秒） */
    private long timeoutSeconds = 10;
    /** 内存硬上限（字节），默认 256MB */
    private long memoryLimit = 256L * 1024 * 1024;
    /** CPU 核数 */
    private long cpuCount = 1;
    /** 进程数硬上限（防 fork 炸弹） */
    private long pidsLimit = 64;
    /** 借容器最长等待（秒） */
    private long borrowTimeoutSeconds = 30;
    /** 宿主机工作目录根 */
    private String workRoot = System.getProperty("user.dir") + "/sandboxWork";
}
