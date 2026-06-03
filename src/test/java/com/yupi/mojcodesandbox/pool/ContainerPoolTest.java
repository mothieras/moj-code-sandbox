package com.yupi.mojcodesandbox.pool;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContainerPoolTest {

    /** 内存版假容器，模拟创建/销毁/健康 */
    static class FakeProvider implements ContainerProvider {
        final AtomicInteger created = new AtomicInteger();
        final Set<String> healthy = ConcurrentHashMap.newKeySet();

        @Override public PooledContainer create() {
            String id = "c" + created.incrementAndGet();
            healthy.add(id);
            return new PooledContainer(id, "/tmp/" + id);
        }
        @Override public void destroy(PooledContainer c) { healthy.remove(c.getContainerId()); }
        @Override public boolean isHealthy(PooledContainer c) { return healthy.contains(c.getContainerId()); }
    }

    @Test
    void warmUp_creates_N_containers() {
        FakeProvider p = new FakeProvider();
        ContainerPool pool = new ContainerPool(p, 3, 1);
        pool.warmUp();
        assertEquals(3, p.created.get());
    }

    @Test
    void borrow_then_giveBack_reuses_same_container() throws Exception {
        FakeProvider p = new FakeProvider();
        ContainerPool pool = new ContainerPool(p, 1, 1);
        pool.warmUp();
        PooledContainer a = pool.borrow();
        pool.giveBack(a);
        PooledContainer b = pool.borrow();
        assertEquals(a.getContainerId(), b.getContainerId());   // 复用同一个
        assertEquals(1, p.created.get());                       // 没新建
    }

    @Test
    void borrow_blocks_then_times_out_when_exhausted() throws Exception {
        FakeProvider p = new FakeProvider();
        ContainerPool pool = new ContainerPool(p, 1, 1);        // borrow 超时 1s
        pool.warmUp();
        pool.borrow();                                          // 拿走唯一一个，不还
        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class, pool::borrow);     // 等满 1s 后抛超时
        assertTrue(System.currentTimeMillis() - start >= 1000);
    }

    @Test
    void unhealthy_container_is_replaced_on_borrow() throws Exception {
        FakeProvider p = new FakeProvider();
        ContainerPool pool = new ContainerPool(p, 1, 1);
        pool.warmUp();
        PooledContainer first = pool.borrow();
        p.destroy(first);                                       // 让它变不健康
        pool.giveBack(first);
        PooledContainer replaced = pool.borrow();
        assertNotEquals(first.getContainerId(), replaced.getContainerId()); // 换了新的
        assertEquals(2, p.created.get());
    }
}
