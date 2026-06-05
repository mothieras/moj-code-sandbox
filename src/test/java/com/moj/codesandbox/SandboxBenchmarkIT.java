package com.moj.codesandbox;

import cn.hutool.core.io.FileUtil;
import com.moj.codesandbox.pool.ContainerExecutor;
import com.moj.codesandbox.pool.ContainerPool;
import com.moj.codesandbox.pool.DockerContainerProvider;
import com.moj.codesandbox.pool.PooledContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.File;

/** 性能基准：容器池复用 vs 每请求新建容器（旧架构），同机同镜像对比 */
@SpringBootTest
class SandboxBenchmarkIT {

    @Autowired ContainerPool pool;
    @Autowired DockerContainerProvider provider;
    @Autowired ContainerExecutor executor;

    private static final String CODE =
            "public class Main { public static void main(String[] a){ System.out.println(\"ok\"); } }";

    /** 在给定容器里跑一次完整判题动作：写码→编译→运行→清理 */
    private void judgeOnce(PooledContainer c) {
        FileUtil.writeUtf8String(CODE, c.getHostWorkDir() + File.separator + "Main.java");
        executor.exec(c.getContainerId(), 10, "javac", "-encoding", "utf-8", "/box/Main.java");
        executor.exec(c.getContainerId(), 10, "java", "-cp", "/box", "Main");
        executor.exec(c.getContainerId(), 5, "sh", "-c", "rm -rf /box/*");
    }

    @Test
    void pooled_vs_per_request_latency() throws Exception {
        int n = 10;

        // 预热一次（排除首轮抖动）
        PooledContainer warm = pool.borrow();
        judgeOnce(warm);
        pool.giveBack(warm);

        // A) 池化：借→判→还
        long pooledTotal = 0, pooledMax = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.currentTimeMillis();
            PooledContainer c = pool.borrow();
            judgeOnce(c);
            pool.giveBack(c);
            long cost = System.currentTimeMillis() - t0;
            pooledTotal += cost;
            pooledMax = Math.max(pooledMax, cost);
        }

        // B) 每请求新建容器（旧架构）：建→判→销毁
        long coldTotal = 0, coldMax = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.currentTimeMillis();
            PooledContainer c = provider.create();
            judgeOnce(c);
            provider.destroy(c);
            long cost = System.currentTimeMillis() - t0;
            coldTotal += cost;
            coldMax = Math.max(coldMax, cost);
        }

        long pooledAvg = pooledTotal / n;
        long coldAvg = coldTotal / n;
        System.out.println("==================== BENCH ====================");
        System.out.printf("每请求新建容器(旧): 平均 %d ms, 最大 %d ms%n", coldAvg, coldMax);
        System.out.printf("容器池复用(新)    : 平均 %d ms, 最大 %d ms%n", pooledAvg, pooledMax);
        System.out.printf("单次判题提速      : %.1fx (节省约 %d ms/次)%n",
                coldAvg / (double) Math.max(1, pooledAvg), coldAvg - pooledAvg);
        System.out.println("===============================================");
    }
}
