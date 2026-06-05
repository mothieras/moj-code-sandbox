package com.moj.codesandbox.pool;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 常驻容器池：预热一批容器，借出/归还/替换坏容器 */
@Slf4j
public class ContainerPool {
    private static final int MAX_BORROW_ATTEMPTS = 3;

    private final ContainerProvider provider;
    private final int size;
    private final long borrowTimeoutSeconds;
    private final BlockingQueue<PooledContainer> idle = new LinkedBlockingQueue<>();
    private final List<PooledContainer> all = new ArrayList<>();

    public ContainerPool(ContainerProvider provider, int size, long borrowTimeoutSeconds) {
        this.provider = provider;
        this.size = size;
        this.borrowTimeoutSeconds = borrowTimeoutSeconds;
    }

    public synchronized void warmUp() {
        for (int i = 0; i < size; i++) {
            PooledContainer c = provider.create();
            all.add(c);
            idle.offer(c);
        }
        log.info("容器池预热完成 size={}", size);
    }

    /** 借一个健康容器；池空则阻塞等待至超时；坏容器自动替换，每次替换后重新借新容器 */
    public PooledContainer borrow() throws InterruptedException {
        int replaced = 0;
        while (true) {
            PooledContainer c = idle.poll(borrowTimeoutSeconds, TimeUnit.SECONDS);
            if (c == null) {
                throw new RuntimeException("借用容器超时，容器池已耗尽");
            }
            if (!provider.isHealthy(c)) {
                if (replaced < MAX_BORROW_ATTEMPTS) {
                    log.warn("容器 {} 不健康，替换 (第{}次)", c.getContainerId(), replaced + 1);
                    replace(c);
                    replaced++;
                    continue;
                }
                throw new RuntimeException("连续" + (replaced + 1) + "个容器不健康，容器池可能不可用");
            }
            return c;
        }
    }

    public void giveBack(PooledContainer c) {
        if (!provider.isHealthy(c)) {
            log.warn("归还容器 {} 时不健康，销毁并替换", c.getContainerId());
            replace(c);
            return;
        }
        idle.offer(c);
    }

    /** 销毁坏容器并补新容器 */
    public synchronized void replace(PooledContainer broken) {
        all.remove(broken);
        try { provider.destroy(broken); } catch (Exception e) { log.error("销毁坏容器失败", e); }
        PooledContainer fresh = provider.create();
        all.add(fresh);
        idle.offer(fresh);
        log.info("已替换容器: {} -> {}", broken.getContainerId(), fresh.getContainerId());
    }

    /** 关机时销毁所有容器 */
    public synchronized void shutdown() {
        for (PooledContainer c : all) {
            try { provider.destroy(c); } catch (Exception e) { log.error("关闭容器失败", e); }
        }
        all.clear();
        idle.clear();
    }
}
