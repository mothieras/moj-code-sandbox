package com.moj.codesandbox.config;

import com.moj.codesandbox.pool.ContainerPool;
import com.moj.codesandbox.pool.DockerContainerProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class PoolConfig {

    private final DockerContainerProvider provider;
    private final SandboxProperties props;

    /** 容器池 Bean：构造时预热，容器销毁时调用 shutdown */
    @Bean(destroyMethod = "shutdown")
    public ContainerPool containerPool() {
        ContainerPool pool = new ContainerPool(
                provider, props.getPoolSize(), props.getBorrowTimeoutSeconds());
        pool.warmUp();
        return pool;
    }
}
