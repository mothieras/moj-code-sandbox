package com.yupi.mojcodesandbox.pool;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 池中一个常驻容器的句柄 */
@Data
@AllArgsConstructor
public class PooledContainer {
    /** Docker 容器 ID */
    private final String containerId;
    /** 该容器专属的宿主机工作目录（绑定到容器内 /box） */
    private final String hostWorkDir;
}
