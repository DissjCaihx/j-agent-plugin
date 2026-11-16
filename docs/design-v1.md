# j-agent-plugin 第一版技术设计

状态：设计稿，未进入实现阶段

## 1. 设计结论

本项目定位为 Java 8 兼容的通用 Java Agent 插件运行时。Agent Core 只负责插件发现、隔离、生命周期、资源托管和本地管理；HTTP、JDBC、RPC、日志、监控等具体能力全部由插件实现。

当前工作区为空，没有现有模块、构建文件或代码约束。本设计因此采用最小可运行的 Maven 多模块结构，后续实现以本设计和用户确认结果为准。

## 2. 技术审查

### 2.1 可行性

- `premain(String, Instrumentation)` 是 Java 8 可用的标准入口。
- 插件独立 `ClassLoader`、`ClassFileTransformer`、线程和资源托管均可在 Java 8 实现。
- `ServiceLoader` 可用于发现 Core 自身的 Bootstrap 组件，但插件发现应使用目录和 `plugin.yaml`，避免把插件业务类型固定在 Core 中。
- 本地 HTTP 管理接口可采用 JDK 8 的 `com.sun.net.httpserver.HttpServer`，首版不需要引入 Spring、Netty 或其他重量级框架。

### 2.2 需要修正或明确的地方

1. `AgentPlugin` 的生命周期方法必须定义异常、幂等和超时语义；Core 不能让插件异常穿透到目标 JVM。
2. 插件类加载器不能简单地完全 Child-First，必须将 JDK、Agent API 和明确的共享 API 保持 Parent-First。
3. 插件之间直接传递实现类会破坏隔离。跨插件通信应通过共享 API 类型、事件对象或 Extension Registry 完成。
4. 停止 Transformer 不等于恢复已修改字节码。首版停止插件只保证移除后续 Transformer；已增强类恢复需要插件配合重转换或重启。
5. “动态管理”首版定义为运行时启停和查询；安装、升级、真正热卸载均重启生效。
6. `plugin.yaml` 解析器需要选定一个 Java 8 兼容的轻量依赖，且解析器应位于 Runtime，不进入 Agent API。
7. 配置、日志和管理接口都必须以插件 ID 为边界，避免 Core 引入业务配置模型。

## 3. Maven 多模块结构

第一阶段采用以下结构，避免过早拆分：

```text
j-agent-plugin/
├── pom.xml
├── agent-api/
├── agent-runtime/
│   └── src/main/java/com/jagent/runtime/
│       ├── classloader/
│       ├── lifecycle/
│       └── manager/
├── agent-bootstrap/
├── agent-assembly/
├── agent-cli/
├── examples/
│   ├── hello-plugin/
│   └── transformer-plugin/
└── docs/
```

依赖方向：

```text
插件示例 ──> agent-api
agent-bootstrap ──> agent-runtime ──> agent-api
agent-cli ──> agent-api（仅使用管理协议模型）
agent-assembly ──> bootstrap + runtime + 依赖
```

`agent-api` 不依赖 Runtime、Bootstrap、CLI 或任何业务框架。后续职责稳定后，才考虑把 Runtime 拆成 Plugin Manager、ClassLoader、Lifecycle 等模块。

## 4. 第一版插件 API

API 只表达通用运行能力：

```java
public interface AgentPlugin {
    PluginDescriptor descriptor();
    void initialize(PluginContext context) throws PluginException;
    void start() throws PluginException;
    void stop() throws PluginException;
}
```

补充约束：

- `descriptor()` 必须是无副作用、可重复调用的元数据读取方法。
- `initialize`、`start`、`stop` 由 Core 串行调用；重复调用必须由 Core 拦截或保证幂等。
- `stop` 由独立超时任务执行，超时后记录错误并继续清理其他资源。
- 插件异常转换为 `PluginException` 或统一运行时错误，不得导致业务 JVM 退出。

`PluginContext` 提供：插件 ID、只读配置视图、插件日志、Instrumentation 管理器、资源管理器、通用 Event Bus、通用 Extension Registry。Context 不提供 Spring、HTTP、JDBC、RPC 等业务对象。

## 5. 插件包与发现

插件目录：

```text
plugins/<plugin-id>/
├── plugin.yaml
├── <plugin>.jar
├── lib/
├── conf/
└── resources/
```

首版 `plugin.yaml` 必需字段：`id`、`version`、`entry-class`、`runtime.java-version`、`compatibility.core-api`、`lifecycle.auto-start`。发现流程为：扫描目录 → 解析元数据 → 校验 ID/版本/入口类 → 校验依赖 → 创建 ClassLoader → 实例化 → 初始化 → 按 `auto-start` 启动。

插件 ID 必须唯一；目录名与 ID 不一致时以元数据为准但记录警告。发现失败只影响当前插件。

## 6. ClassLoader 设计

每个插件一个 `PluginClassLoader`，父加载器为提供 `agent-api` 的 API ClassLoader。

委派规则：

- `java.*`、`javax.*`、Agent API 包：Parent-First。
- 插件自身包和插件私有依赖：Child-First。
- 明确声明的共享扩展 API：由 API ClassLoader 提供。
- Runtime 内部实现包：不暴露给插件。

所有进入插件代码的同步、异步和定时执行路径都通过 TCCL 包装器设置插件 ClassLoader，并在 `finally` 中恢复原值。插件停止时必须注销任务、关闭线程池、清除订阅和引用，避免 ClassLoader 泄漏。

## 7. 生命周期状态机

```text
DISCOVERED -> VALIDATED -> LOADED -> INITIALIZED -> STARTING -> ACTIVE
                                                        |
                                                        v
                                               STOPPING -> STOPPED
任意启动阶段 ----------------------------------------------> FAILED
```

状态变更由 Lifecycle Manager 单点负责；每次变更记录时间、原因和异常。插件失败隔离，Core 初始化失败则根据启动策略返回明确错误：`fail-fast` 或 `degraded`。MVP 默认采用 `degraded`，使目标应用仍可启动，同时输出清晰错误。

## 8. Transformer 管理

插件通过 `InstrumentationManager` 注册 Transformer。Runtime 保存插件 ID、实例、注册时间、`canRetransform` 和错误信息，并在插件停止时调用 `removeTransformer`。

首版保证：

- Transformer 所属关系可查询。
- 插件停止后不再接收新的类变换回调。
- 注册和移除操作线程安全。
- 单个 Transformer 异常被记录并隔离。

首版不承诺自动还原已增强类；验收中必须加入重转换和多 Transformer 顺序测试。

## 9. 通用扩展与事件

Extension Registry 使用 `(extensionType, pluginId)` 作为注册边界，支持注册、查找、查找全部和按插件注销。Core 不声明 `HttpExtension`、`RpcExtension` 等业务接口；接口类型由插件或共享 API 自行定义。

Event Bus 首版先实现进程内、类型过滤、异常隔离和订阅注销。同步发布作为默认策略；异步、有界队列和背压作为明确的后续扩展，避免 MVP 引入未验证的线程模型。

## 10. 本地管理

Runtime 默认只监听 `127.0.0.1`。第一版管理面最小集合：插件列表、详情、状态、启动、停止、运行时状态。管理 API 与 CLI 之间共享稳定的命令模型，但 CLI 不直接依赖 Runtime 内部类。

认证令牌、配置文件权限和命令授权必须在首版本地管理实现中保留扩展点；未配置认证时只允许回环地址访问，并在启动日志中明确提示。

## 11. MVP 实施顺序与验收

1. 工程骨架与 Java 8 Maven 编译。
2. Bootstrap + Runtime 初始化，验证普通 Java 应用可通过 `-javaagent` 启动。
3. 插件 YAML、目录扫描、独立 ClassLoader 和入口加载。
4. 生命周期、状态查询、失败隔离和资源清理。
5. Extension Registry。
6. Event Bus。
7. Transformer 注册、移除和行为测试。
8. 本地管理 API 与最小 CLI。
9. 组装 Agent JAR、示例插件和集成测试。

最低验收标准：Java 8 编译通过；无 Spring/Netty/Nacos/Sermant 强制依赖；Hello 插件可自动启动和停止；失败插件不影响目标应用；插件私有依赖不污染应用；Transformer 可注册和移除；关闭时线程、订阅、扩展和 Transformer 均可追踪清理。

## 12. 当前实现进度

MVP 已实现 Maven 多模块工程、公开插件 API、Agent `premain` 和 `agentmain` 入口、全局 `agent.yaml` 插件启用清单、可配置的结构化 Runtime/插件日志、YAML 元数据校验、插件版本依赖顺序、独立 ClassLoader、生命周期和停止超时、资源与 Transformer 清理、通用扩展注册、同步事件总线、本地令牌认证管理 API、CLI、ZIP 插件安全安装与升级暂存、Properties 配置及显式 opt-in 的配置热更新。`agent-plugins` 提供原生插件集合，`agent-plugin-bundle` 将原生插件整理到可扫描目录，`agent-distribution` 生成包含 Agent、全局配置和插件目录的发行包。Runtime 源文件按职责分目录。

在 JDK 8u511 上，完整 Maven 构建、27 项 Runtime 测试、`premain` 与真实 Attach/`agentmain` 插件启动/关闭、CLI 帮助命令、本地 CLI 安装/升级/重启激活链路，以及发行目录直接扫描并启动原生插件均已验证。稳定性测试覆盖依赖顺序/缺失/循环、并发启动幂等、资源关闭、ClassLoader 隔离、Transformer 移除及管理认证。仍未覆盖的后续产品能力包括异步事件与背压、通用 SemVer 范围、长时间压力/泄漏测试和自动撤销已增强字节码。
