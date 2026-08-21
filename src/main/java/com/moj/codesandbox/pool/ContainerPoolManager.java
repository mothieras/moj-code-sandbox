package com.moj.codesandbox.pool;

import com.github.dockerjava.api.DockerClient;
import com.moj.codesandbox.config.SandboxProperties;
import com.moj.codesandbox.model.LanguageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多语言容器池路由：按语言懒加载独立 ContainerPool（每语言一个镜像）。
 * 首次 borrow(language) 时才创建并预热该语言池，不用的语言不占容器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerPoolManager {

    private final DockerClient dockerClient;
    private final SandboxProperties props;
    private final Map<String, ContainerPool> pools = new ConcurrentHashMap<>();

    public PooledContainer borrow(String language) throws InterruptedException {
        return getPool(language).borrow();
    }

    public void giveBack(String language, PooledContainer container) {
        getPool(language).giveBack(container);
    }

    /** 销毁坏容器并补新容器（委托该语言池） */
    public void replace(String language, PooledContainer broken) {
        getPool(language).replace(broken);
    }

    private ContainerPool getPool(String language) {
        return pools.computeIfAbsent(language, this::createPool);
    }

    private ContainerPool createPool(String language) {
        LanguageConfig cfg = LanguageConfig.of(language);
        DockerContainerProvider provider =
                new DockerContainerProvider(dockerClient, props, cfg.getImage());
        ContainerPool pool = new ContainerPool(provider, props.getPoolSize(), props.getBorrowTimeoutSeconds());
        pool.warmUp();
        log.info("语言 [{}] 容器池预热完成 image={} size={}", language, cfg.getImage(), props.getPoolSize());
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(ContainerPool::shutdown);
        pools.clear();
    }
}
