# MOJ Code Sandbox — 代码沙箱服务

[MOJ 在线判题系统](https://github.com/mothieras/moj-online-judge) 的独立**代码沙箱**服务：在 Docker 容器隔离环境中编译、运行不可信的用户提交代码，采集输出 / 耗时 / 内存 / 异常，并通过 HTTP 接口对外提供判题执行能力。

## 核心亮点

- **常驻容器池复用** —— 从「每次请求新建 / 销毁容器」重构为**预热容器池 + 借还机制**：复用前清理工作目录做隔离、归还时健康检查自动替换坏容器、清理失败直接销毁容器，消除容器创建 / 销毁开销。同机同镜像基准下，单次执行平均延迟 **≈ 3.0s → 0.76s（约 4× 提速）**。
- **多层安全加固** —— 内存上限 + 禁用 swap、`pids-limit` 防 fork 炸弹、容器以非 root 运行、只读根文件系统、禁用网络。
- **隔离执行健壮性** —— 分离 stdout/stderr 正确识别运行时异常；编译移入容器，避免宿主 / 容器 JDK 版本不一致；执行超时强制 kill 失控进程；cgroup v2 下采样内存峰值。
- **设计模式** —— 模板方法 `BaseCodeSandboxTemplate` 固化「保存 → 编译 → 运行 → 收集 → 清理」骨架，Java / Python 两种 Docker 沙箱实现复用同一模板。

## 技术栈

- **Java 17** + **Spring Boot**
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

> 前置：本机 Docker 守护进程运行中，并已拉取执行镜像 `amazoncorretto:17-alpine`。

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
| `sandbox.image` | `amazoncorretto:17-alpine` | 执行用镜像 |
| `sandbox.timeout-seconds` | 10 | 单次执行超时 |
| `sandbox.memory-limit` | 268435456 | 容器内存上限（字节，256 MB） |
| `sandbox.cpu-count` | 1 | CPU 配额 |
| `sandbox.pids-limit` | 64 | 进程数上限（防 fork 炸弹） |
| `sandbox.borrow-timeout-seconds` | 30 | 借用容器超时 |

## 容器隔离参数

| Docker 参数 | 值 | 防护目的 |
|------------|-----|---------|
| `--memory` | 256 MB | 硬内存上限，OOM Killer 触发终止 |
| `--memory-swap` | =memory | 禁用 swap，内存限制严格生效 |
| `--pids-limit` | 64 | 防 fork 炸弹耗尽宿主机 PID |
| `--read-only` | true | 根文件系统只读，仅 /box 可写 |
| `--network=none` | true | 无网络访问 |
| `--user` | nobody | 非 root 运行 |
| `--tty` | false | 禁用 TTY，保证 stdout/stderr 分离 |

## 输入校验

| 规则 | 限制值 | 说明 |
|------|--------|------|
| 代码长度 | ≤ 64 KB | 防止超大代码文件 |
| 语言白名单 | 仅 `java` | 只支持 Java，其他语言返回错误 |
| 输入用例数量 | ≤ 100 | 防止用例轰炸 |
| 单条输入长度 | ≤ 10 KB | 防止单条输入过大 |

## 安全用例

沙箱包含以下安全边界测试（通过 `JavaDockerCodeSandboxIT` 验证）：

| 攻击场景 | 测试方式 | 预期结果 |
|---------|---------|---------|
| 无限内存分配 (OOM) | Java 程序持续分配内存 | 容器内存超限被 kill，返回 status=3 |
| 读取宿主文件 | 尝试 `new FileReader("/etc/passwd")` | 只读根文件系统 + 无权限用户 → 执行失败 |
| 写入非 /box 目录 | 尝试写 `/tmp/malicious` | 只读根文件系统 → 写入失败 |
| 执行外部程序 | `Runtime.exec("rm -rf /")` | pids-limit + nobody → 执行失败或无效 |
| Fork 炸弹 | 循环 fork 子进程 | pids-limit=64 → 进程数上限触发 |
| 无限循环 / sleep | `while(true)` 或 `Thread.sleep(3600000)` | 超时后被 kill，返回 Timeout |
| 网络访问 | 尝试 `new URL("http://evil.com")` | 网络禁用 → 连接失败 |

## 测试

19 个单元测试 + 5 个集成测试。集成测试（`*IT`）经 failsafe 在 `verify` 阶段运行，需要本机 Docker：

```bash
# 单元测试（无需 Docker）
./mvnw test

# 全量测试（含集成测试）
./mvnw verify
```
