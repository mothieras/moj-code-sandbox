# code-sandbox — 多语言代码沙箱服务

独立的**多语言代码沙箱**服务：在 Docker 容器隔离环境中编译、运行不可信的用户提交代码，采集输出 / 耗时 / 内存 / 异常，并通过 HTTP 接口对外提供代码执行能力。支持 **Java / Python / C++**。

## 核心亮点

- **多语言通用** —— 由 `LanguageConfig` 驱动各语言的镜像 / 文件名 / 编译运行命令 / 超时 kill 目标，加新语言只改一处配置。
- **多镜像多池，按需懒加载** —— 每种语言独立镜像 + 独立容器池，首次请求该语言才预热；不用的语言不占容器。借还复用、坏容器自动替换、清理失败即销毁。
- **常驻容器池复用** —— 从「每请求新建 / 销毁容器」重构为预热容器池 + 借还机制，消除容器创建 / 销毁开销。
- **多层安全加固** —— 内存上限 + 禁 swap、`pids-limit` 防 fork 炸弹、只读根、禁网、非 root、`no-new-privileges` + `cap-drop=ALL` 收窄 capabilities；超时按语言进程名 kill。
- **隔离执行健壮性** —— 分离 stdout/stderr 识别运行时异常；编译移入容器；执行超时强制 kill 残留进程；cgroup v2 下采样内存峰值。
- **入口边界控制** —— HTTP 层鉴权、语言白名单（由 `LanguageConfig` 动态生成）、代码长度、用例数量和单条输入长度校验。

## 技术栈

- **Java 17** + **Spring Boot 2.7**
- **docker-java** — 以编程方式驱动 Docker 容器
- **Hutool** — 通用工具

## 架构

```
HTTP 请求 (POST /executeCode)
        ↓
MainController                     ← 鉴权 + 输入边界校验（语言白名单由 LanguageConfig 生成）
        ↓
DockerCodeSandbox                  ← 语言无关执行链路
        ↓
LanguageConfig.of(language)        ← 取镜像 / 文件名 / 编译·运行模板 / killTarget
        ↓
ContainerPoolManager.borrow(lang)  ← 按语言懒加载对应镜像的 ContainerPool
        ↓
ContainerExecutor                  ← docker exec（argv 模式）：编译 + 运行，分流 stdout/stderr，
                                     超时按 killTarget kill，采集耗时 / 内存峰值
        ↓
清理 /box → giveBack → 删宿主机文件
        ↓
ExecuteCodeResponse (outputList / status / judgeInfo)
```

关键类：`DockerCodeSandbox`（语言无关执行）、`ContainerPoolManager`（多语言池路由）、`ContainerPool`（借 / 还 / 替换坏容器）、`DockerContainerProvider`（按镜像创建容器）、`ContainerExecutor`（容器内执行与采集）、`LanguageConfig`（语言配置枚举）。

## 支持的语言

| 语言 | 镜像 | 源码文件名 | 编译 | 超时 kill 目标 |
|------|------|-----------|------|----------------|
| Java | `amazoncorretto:17-alpine` | `Main.java` | `javac` | `java` |
| Python | `python:3.12-alpine` | `code.py` | 无（解释执行） | `python3` |
| C++ | `gcc:13-bookworm` | `Main.cpp` | `g++` | `main` |

镜像由 `LanguageConfig` 提供；运行 / 编译命令模板的占位符在容器内解析：`{srcFile}` → `/box/<文件名>`、`{srcDir}` → `/box`、`{args}` → 输入参数。

## 快速启动

> 前置：本机 Docker 守护进程运行中。各语言镜像在首次执行该语言时自动拉取（可预拉减少首次等待）。

```bash
./mvnw spring-boot:run     # 监听 :8090；启动时不预热容器池（按需懒加载）
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

Python 示例：

```json
{ "language": "python", "code": "a, b = map(int, input().split())\nprint(a + b)", "inputList": ["1 2", "3 4"] }
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
| `sandbox.pool-size` | 5 | 每语言容器池大小 |
| `sandbox.timeout-seconds` | 10 | 单次执行超时 |
| `sandbox.memory-limit` | 268435456 | 容器内存上限（字节，256 MB） |
| `sandbox.cpu-count` | 1 | CPU 配额 |
| `sandbox.pids-limit` | 64 | 进程数上限（防 fork 炸弹） |
| `sandbox.borrow-timeout-seconds` | 30 | 借用容器超时 |

> 运行镜像不再在此配置，由 `LanguageConfig` 按语言提供。

## 容器隔离参数

| Docker 参数 | 值 | 防护目的 |
|------------|-----|---------|
| `--memory` | 256 MB | 硬内存上限，OOM Killer 触发终止 |
| `--memory-swap` | =memory | 禁用 swap，内存限制严格生效 |
| `--pids-limit` | 64 | 防 fork 炸弹耗尽宿主机 PID |
| `--read-only` | true | 根文件系统只读，仅 /box 可写 |
| `--network=none` | true | 无网络访问 |
| `--user` | nobody | 非 root 运行 |
| `--security-opt` | `no-new-privileges:true` | 禁止容器内提权 |
| `--cap-drop` | `ALL` | 丢弃所有 Linux capabilities |
| `--tty` | false | 禁用 TTY，便于 stdout/stderr 分离 |

## 输入校验

| 规则 | 限制值 | 说明 |
|------|--------|------|
| 代码长度 | ≤ 64 KB | 防止超大代码文件 |
| 语言白名单 | java / python / cpp | 由 `LanguageConfig` 动态生成，其他语言返回错误 |
| 输入用例数量 | ≤ 100 | 防止用例轰炸 |
| 单条输入长度 | ≤ 10 KB | 防止单条输入过大 |

## 安全与健壮性验证

| 边界 | 实现方式 | 当前验证 |
|------|----------|----------|
| 无限循环 / sleep | `awaitCompletion` 超时 + 按语言 `pkill -9 <killTarget>` | 三语言均已覆盖 |
| 无限内存分配 | `--memory` + `--memory-swap = memory` | 已覆盖（Java） |
| 运行时异常识别 | 禁用 TTY，分离 stdout/stderr | 已覆盖 |
| 容器生命周期 | 创建、健康检查、销毁 | 已覆盖 |
| 命令注入 | 用户命令走 `docker exec` argv 模式，不经 shell | 设计约束 |
| 提权 / capabilities | `no-new-privileges` + `cap-drop=ALL` | 参数已配置 |
| Fork 炸弹 | `--pids-limit=64` | 参数已配置 |
| 写非 `/box` 目录 | `--read-only` 根文件系统，仅挂载 `/box` | 参数已配置 |
| 网络访问 | `--network=none` | 参数已配置 |
| 权限收窄 | `--user nobody` | 参数已配置 |

## 测试

- **单元测试**（无需 Docker）：`MainControllerTest`、`ContainerPoolTest`。
- **集成测试**（`*IT`，需本机 Docker，经 failsafe 在 `verify` 阶段运行）：`DockerCodeSandboxIT`（三语言各覆盖正常 / 编译错误 / 运行时异常 / 超时）、`ContainerExecutorIT`、`DockerContainerProviderIT`、`SandboxBenchmarkIT`、`MojCodeSandboxApplicationIT`。

```bash
# 单元测试（无需 Docker）
./mvnw test

# 全量测试（含集成测试，首次会拉取 python:3.12-alpine 与 gcc:13-bookworm）
./mvnw verify
```
