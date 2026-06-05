package com.moj.codesandbox.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerClientConfig {

    /** 全局唯一 DockerClient，容器关闭时自动 close */
    @Bean(destroyMethod = "close")
    public DockerClient dockerClient() {
        return DockerClientBuilder.getInstance().build();
    }
}
