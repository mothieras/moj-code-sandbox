# MOJ Code Sandbox — 代码沙箱服务

[MOJ 在线判题系统](https://github.com/mothieras/moj-online-judge) 的独立**代码沙箱**服务：在 Docker 容器隔离环境中编译、运行不可信的用户提交代码，采集输出 / 耗时 / 内存 / 异常，并通过 HTTP 接口对外提供判题执行能力。

## 核心亮点

- **常驻容器池复用** —— 从「每次请求新建 / 销毁容器」重构为**预热容器池 + 借还机制**：复用前清理工作目录做隔离、健康检查自动替换坏容器，消除容器创建 / 销毁开销。同机同镜像基准下，单次执行平均延迟 **≈ 3.0s → 0.76s（约 4× 提速）**。
- **多层安全加固** —— 内存上限 + 禁用 swap、`pids-limit` 防 fork 炸弹、容器以非 root 运行、只读根文件系统、禁用网络。
- **隔离执行健壮性** —— 分离 stdout/stderr 正确识别运行时异常；编译移入容器，避免宿主 / 容器 JDK 版本不一致；执行超时强制 kill 失控进程；cgroup v2 下采样内存峰值。
- **设计模式** —— 模板方法 `BaseCodeSandboxTemplate` 固化「保存 → 编译 → 运行 → 收集 → 清理」骨架，Java / Python 两种 Docker 沙箱实现复用同一模板。

## 技术栈

- **Java 8** + **Spring Boot**
- **docker-java** — 以编程方式驱动 Docker 容器
- **Hutool** — 通用工具

## 架构

```
HTTP 请求 (POST /executeCode)
        ↓
JavaDockerCodeSandbox              ← 模板方法编排
        ↓
ContainerPool.borrow()            ← 从池借一个健康常驻容器（阻塞至超时；坏容器自动替换）
        ↓
ContainerExecutor                 ← docker exec：/box 内编译 + 运行，分流 stdout/stderr，
                                    超时 kill，采集耗时 / 内存峰值
        ↓
清理 /box → ContainerPool.giveBack()   ← 归还容器供下次复用
        ↓
ExecuteCodeResponse (outputList / status / judgeInfo)
```

容器池关键类：`ContainerPool`（借 / 还 / 替换坏容器）、`ContainerProvider` / `DockerContainerProvider`（容器生命周期）、`PooledContainer`（池中容器）、`ContainerExecutor`（容器内执行与采集）。

## 快速启动

> 前置：本机 Docker 守护进程运行中，并已拉取执行镜像 `eclipse-temurin:8-jdk-alpine`。

```bash
./mvnw spring-boot:run     # 监听 :8090，启动时预热容器池
```

## 接口

```http
POST /executeCode
auth: <鉴权密钥>
Content-Type: application/json

{
  "code": "public class Main { public static void main(String[] a){ ... } }",
  "inputList": ["1 2", "3 4"],
  "language": "java"
}
```

响应：

```json
{
  "outputList": ["3", "7"],
  "message": "...",
  "status": 1,
  "judgeInfo": { "time": 760, "memory": 12345678 }
}
```

另有 `GET /health` 健康检查（返回 `ok`）。

## 配置（`application.yml`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `sandbox.pool-size` | 5 | 常驻容器池大小 |
| `sandbox.image` | `eclipse-temurin:8-jdk-alpine` | 执行用镜像 |
| `sandbox.timeout-seconds` | 10 | 单次执行超时 |
| `sandbox.memory-limit` | 268435456 | 容器内存上限（字节，256 MB） |
| `sandbox.cpu-count` | 1 | CPU 配额 |
| `sandbox.pids-limit` | 64 | 进程数上限（防 fork 炸弹） |
| `sandbox.borrow-timeout-seconds` | 30 | 借用容器超时 |

## 测试

16 个测试（单元 + 集成）。集成测试（`*IT`）经 failsafe 在 `verify` 阶段运行，需要本机 Docker：

```bash
./mvnw verify
```
