package com.moj.codesandbox.pool;

import com.moj.codesandbox.config.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DockerContainerProviderIT {

    @Autowired DockerContainerProvider provider;
    @Autowired SandboxProperties props;

    @Test
    void create_then_healthy_then_destroy() {
        PooledContainer c = provider.create();
        try {
            assertNotNull(c.getContainerId());
            assertTrue(provider.isHealthy(c));               // 刚建的应当健康
        } finally {
            provider.destroy(c);
        }
        assertFalse(provider.isHealthy(c));                  // 销毁后不健康
    }
}
