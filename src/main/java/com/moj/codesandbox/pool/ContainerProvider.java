package com.moj.codesandbox.pool;

/** 容器的创建/销毁/健康检查，抽象出来便于池逻辑单测 */
public interface ContainerProvider {
    /** 创建并启动一个常驻容器 */
    PooledContainer create();
    /** 销毁容器并清理其工作目录 */
    void destroy(PooledContainer container);
    /** 容器是否仍健康可用 */
    boolean isHealthy(PooledContainer container);
}
