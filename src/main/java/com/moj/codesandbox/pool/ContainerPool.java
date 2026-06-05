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

    /** 借一个健康容器；池空则阻塞等待至超时；坏容器自动替换 */
    public PooledContainer borrow() throws InterruptedException {
        PooledContainer c = idle.poll(borrowTimeoutSeconds, TimeUnit.SECONDS);
        if (c == null) {
            throw new RuntimeException("借用容器超时，容器池已耗尽");
        }
        if (!provider.isHealthy(c)) {
            log.warn("容器 {} 不健康，替换", c.getContainerId());
            replace(c);
            return borrow();
        }
        return c;
    }

    public void giveBack(PooledContainer c) {
        idle.offer(c);
    }

    private synchronized void replace(PooledContainer broken) {
        all.remove(broken);
        try { provider.destroy(broken); } catch (Exception e) { log.error("销毁坏容器失败", e); }
        PooledContainer fresh = provider.create();
        all.add(fresh);
        idle.offer(fresh);
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
