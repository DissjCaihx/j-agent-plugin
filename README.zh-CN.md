# j-agent-plugin

兼容 Java 8 的 Java Agent
插件运行时。核心负责插件发现、隔离类加载、生命周期管理、资源清理和本地管理；具体功能能力由插件提供。

## 构建

``` sh
mvn clean verify
```

`agent-bootstrap` 模块会生成可执行的 Agent
JAR，其中包含运行时依赖，并配置 `Premain-Class` Manifest 属性。CLI
构建产物位于 `agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar`。

执行 `mvn clean verify` 后，会在仓库根目录生成 `j-agent-plugin/`，并在
`agent-distribution/target/` 下生成 ZIP 包
`agent-distribution-0.1.0-SNAPSHOT.zip`。两者均包含
`agent.jar`、`agent.yaml` 和可直接扫描的 `plugins/` 目录。原生插件位于
`agent-plugins/` 下；`agent-plugin-bundle` 会在 `package` 阶段将各插件
JAR 和描述文件整理到发行包目录结构中。

## 使用 Spring Boot Nacos 插件

先构建发行包，再复制 Nacos 插件的示例配置文件：

``` sh
mvn clean package
cd j-agent-plugin
cp plugins/springboot-nacos/conf/config.properties.example \
  plugins/springboot-nacos/conf/config.properties
```

编辑
`plugins/springboot-nacos/conf/config.properties`。对于本项目示例中的
Nacos 环境，连接地址和命名空间等配置可以如下设置：

``` properties
nacos.enabled=true
nacos.server-addr=127.0.0.1:8848
nacos.namespace=GBM-Space
nacos.username=${NACOS_USERNAME}
nacos.password=${NACOS_PASSWORD}
nacos.discovery.enabled=true
nacos.discovery.register.enabled=true
nacos.config.enabled=true
nacos.config.data-ids=<an-existing-DataId>
nacos.config.group=DEFAULT_GROUP
```

`GBM-Space` 是"GBM中台服务"对应的 Namespace
ID；如果使用"GBM网关"，则应填写其 Namespace
ID：`4fb59a5e-0999-4013-bff2-a8ad525e96ef`。请使用所选命名空间中已经存在的
DataId。如果应用不需要使用 Nacos Config，可设置
`nacos.config.enabled=false`。

在 Shell 中设置认证信息，避免将密码直接写入命令历史：

``` sh
export NACOS_USERNAME=nacos
read -s NACOS_PASSWORD
export NACOS_PASSWORD
```

全局 `agent.yaml` 已经启用了 `springboot-nacos`。启动 Spring Boot
应用时，必须在 Spring 启动之前加载 Agent：

``` sh
java -javaagent:/path/to/j-agent-plugin/agent.jar \
  -jar /path/to/your-spring-boot-app.jar
```

插件会在 Spring Boot 启动后，从 Spring Boot WebServer
读取实际绑定的端口。服务名称优先从
`spring.cloud.nacos.discovery.service` 获取，否则读取
`spring.application.name`；随后从 Spring Environment 中读取
group、cluster、weight、ephemeral 和 metadata 等设置。

如果存在 `spring.cloud.nacos.discovery.ip`，插件会使用它作为对外公布的
IP；也可以在插件属性中配置
`nacos.discovery.ip`。如果两者均未设置，则使用非通配符的
`server.address`。当应用绑定到 `0.0.0.0` 或 `::`
时，必须显式配置对外公布的 IP。Spring
上下文关闭时，插件会注销已注册的服务实例。

如需使用本地管理 API，请添加以下 JVM 参数，并设置自己的 Token：

``` sh
java -Djagent.management.port=8787 \
  -Djagent.management.token=change-me \
  -javaagent:/path/to/j-agent-plugin/agent.jar \
  -jar /path/to/your-spring-boot-app.jar
```

然后在项目工作区中使用 CLI：

``` sh
java -Djagent.url=http://127.0.0.1:8787 \
  -Djagent.token=change-me \
  -jar /path/to/j-agent-plugin/agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar list
```

可使用以下命令查看和管理插件：

-   `list`：列出插件。
-   `start <plugin-id>`：启动指定插件。
-   `stop <plugin-id>`：停止指定插件。
-   `reload-config <plugin-id>`：重新加载指定插件的配置。

插件事件总线请求和 Nacos 变更通知用于 Agent
插件之间的通信；它们不会自动刷新应用程序的 Spring Environment 或 Spring
Bean。

## 运行示例

构建项目后，可直接使用发行目录中附带的插件目录：

``` sh
java -Djagent.management.port=8787 -Djagent.management.token=change-me \
  -javaagent:j-agent-plugin/agent.jar \
  -jar your-application.jar
```

如果没有为 Agent 指定插件目录参数，扫描器会扫描 `agent.jar` 同级目录下的
`plugins/`。如需使用其他目录，可按以下方式传入：

``` sh
-javaagent:/path/to/agent.jar=/path/to/plugins
```

同级目录中的 `agent.yaml` 控制插件自动启动。发行包默认启用
`runtime-info` 和 `springboot-nacos`：

``` yaml
plugins:
  enabled:
    - runtime-info
    - springboot-nacos
```

`springboot-nacos` 提供 Nacos
服务注册与发现，以及配置获取和监听能力。插件使用隔离的 Nacos 2.5.5
客户端。发行包将客户端私有依赖放在 `plugins/springboot-nacos/lib/`
下。复制 `plugins/springboot-nacos/conf/config.properties.example` 为
`config.properties`，配置 `nacos.server-addr`，启用所需能力，并通过
`NACOS_USERNAME` / `NACOS_PASSWORD` 设置认证信息。

启用服务注册后，插件会在 Spring Boot WebServer
启动后读取实际端口和服务信息，不会使用猜测的端口。

插件通过共享事件总线传递 `java.util.Map`
消息，使不同隔离类加载器中的其他插件也能够互操作。可发布以下请求：

-   服务发现请求：`action=springboot-nacos.discovery.query|subscribe|unsubscribe`，并提供
    `serviceName`（可选 `groupName`）。
-   配置请求：`action=springboot-nacos.config.get|subscribe|unsubscribe`，并提供
    `dataId`（可选 `group`）。

插件会将 `success` 和响应字段写回同一个 Map。变更通知通过以下类型发布：

-   `type=springboot-nacos.discovery.changed`
-   `type=springboot-nacos.config.changed`

配置变更内容放在 `content` 字段中，不会写入日志。订阅方可订阅
`Map.class`，并根据 `type` 字段筛选通知。

## 使用 Sermant Backend 集成插件

`sermant-backend` 插件会将 Sermant 兼容的心跳和 WARN/ERROR
级别日志事件直接发送到 Backend Netty 网关。它不使用 Backend Web UI
端口（通常为 `8900`）。

请启用 Backend 网关，并确保 Agent 所在主机可以访问其 Netty 端口（默认
`6888`）。然后复制并编辑插件配置：

``` sh
cp plugins/sermant-backend/conf/config.properties.example \
   plugins/sermant-backend/conf/config.properties
```

将 `sermant.backend.address` 和 `sermant.backend.port` 设置为 Backend
网关地址和端口。插件会在可用时从运行中的 Spring Boot 上下文读取
`spring.application.name`；对于非 Spring 应用，请设置
`sermant.agent.service-name` 和 `sermant.agent.app-name`。

将 `sermant-backend` 添加到 `agent.yaml` 的 `plugins.enabled`
列表中，即可启动该插件。当前实现会上报心跳身份信息以及 WARN/ERROR
日志事件；**不会**将完整日志文件或主机/JVM 指标发送到 Sermant Backend。

传输协议遵循 Sermant Netty 网关线格式：使用长度字段分隔的 Protobuf
信封，其中包含经过 Gzip 压缩的 JSON
心跳或事件负载。插件使用有界日志事件队列，在配置的时间间隔内对重复消息进行去重，并在网关连接失败时重试。插件停止时，会移除其注册的
JUL/Logback 日志捕获钩子。

## 插件启用与日志配置

将插件 ID 添加到 `plugins.enabled`
列表即可启动对应插件；如果该列表为空，则不会启动任何插件。被选中插件的必需依赖会自动启动。其他已发现的插件仍会被加载和初始化，但不会执行
`start()` 回调。

可通过以下 JVM 参数指定配置文件：

``` sh
-Djagent.config=/path/to/agent.yaml
```

如果配置文件不存在，则使用各插件自身的 `lifecycle.auto-start` 设置。

运行时日志包含时间戳、日志级别、线程、组件/插件 ID 和异常堆栈。可在
`agent.yaml` 中通过 `logging.level` 设置日志级别，支持：

-   `SEVERE`
-   `WARNING`
-   `INFO`
-   `FINE`
-   `FINER`
-   `FINEST`

也可以通过 `-Djagent.log.level=FINE`
覆盖配置文件中的级别。系统属性优先级更高。

Agent 日志与应用日志分开保存，默认路径为：

``` text
<agent-directory>/logs/j-agent-plugin.0.log
```

日志文件达到 10 MiB 时滚动，最多保留五个文件。可使用以下命令查看日志：

``` sh
tail -f j-agent-plugin/logs/j-agent-plugin.0.log
```

可通过 `-Djagent.log.dir=/path/to/logs` 修改日志目录。如果 Agent
目录不可写，日志会回退到 `~/.j-agent-plugin/logs/`。

Nacos 客户端初始化、Spring 上下文配置值、Hook
失败、服务注册错误和异常堆栈都会记录在 Agent 日志中。密码和 Token
会进行脱敏处理。同一批日志也会输出到控制台。

示例插件仍作为独立模块保留在 `examples/hello-plugin`
下，不包含在原生插件集合中。

## 本地管理服务与 API

管理服务仅绑定到 `127.0.0.1`。如果不设置
`jagent.management.port`，管理服务将保持禁用状态。启用管理服务时，必须同时设置非空
Token。

每个 API 请求都必须在 `X-Agent-Token` 请求头中携带该 Token。

同一个 Agent JAR 也可以通过 JDK Attach API 附加到正在运行的 JVM：

``` java
VirtualMachine vm = VirtualMachine.attach(targetPid);
try {
    vm.loadAgent("/path/to/agent-bootstrap-0.1.0-SNAPSHOT.jar", "/path/to/plugins");
} finally {
    vm.detach();
}
```

Agent Manifest 同时声明了 `Premain-Class` 和
`Agent-Class`。两个入口共用幂等初始化逻辑，因此在同一个 JVM 中重复
Attach 会被忽略。

CLI 示例：

``` sh
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar list
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar start hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar stop hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar reload-config hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar install /path/to/plugin.zip
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar update hello-plugin /path/to/plugin.zip
```

HTTP API 端点如下：

  -------------------------------------------------------------------------------
  方法                    端点                            说明
  ----------------------- ------------------------------- -----------------------
  `GET`                   `/plugins`                      获取插件列表

  `GET`                   `/plugins/{id}`                 获取指定插件信息

  `POST`                  `/plugins/{id}/start`           启动插件

  `POST`                  `/plugins/{id}/stop`            停止插件

  `POST`                  `/plugins/{id}/config/reload`   重新加载插件配置

  `POST`                  `/plugins/install`              安装插件

  `POST`                  `/plugins/{id}/update`          更新插件，使用 ZIP
                                                          请求体，最大 128 MiB
  -------------------------------------------------------------------------------

## 插件目录结构

``` text
plugins/<id>/
├── plugin.yaml
├── plugin.jar
├── lib/                 # 可选：插件私有依赖
└── conf/config.properties
```

`plugin.yaml` 可以通过 `dependencies.plugins` 声明插件依赖。每项依赖包含
`id` 和版本，支持以下版本形式：

-   `*`：任意版本。
-   精确版本：指定完整版本号。
-   主版本前缀：例如 `2.x`。

运行时会校验依赖是否存在及其版本，并按依赖顺序加载插件，同时检测并拒绝循环依赖。依赖提供方必须处于
`ACTIVE`
状态后，依赖它的插件才能启动。如果某个提供方插件仍有处于活动状态的消费者插件，则拒绝停止该提供方插件。

插件安装包必须包含 `plugin.yaml`，并且包含一个或多个插件 JAR。插件 JAR
可以位于压缩包根目录，也可以位于 `lib/` 目录中。

上传文件最大为 128 MiB。系统会检查 ZIP
路径穿越、条目数量、解压后大小、描述文件有效性以及入口类兼容性。

现有插件升级时，会先暂存到 `.pending/`，并在 JVM
重启前保留当前安装版本。下一次启动时执行版本切换，旧版本会移动到
`.backups/` 目录。

插件配置在初始化阶段从 `conf/config.properties` 读取。插件可通过实现
`ConfigurablePlugin`
接口选择支持热更新。重新加载时，回调会收到一份候选配置；只有回调成功后，候选配置才会成为当前配置。

## 插件隔离与资源管理

每个插件都会获得一个 child-first 类加载器。以下类型采用 parent-first
加载，以保持共享类型身份，并避免 Runtime 内部类被复制到插件中：

-   JDK 类型。
-   Agent API 类型。
-   Runtime 类型。

**类加载器隔离不等同于安全沙箱。**

插件停止时，运行时会移除已注册的
Transformers，并清理已托管的资源；但对于已经完成的字节码转换，不能保证恢复到转换前的状态。

插件安装和升级通过本地 API/CLI 完成。升级会先暂存，必须在目标 JVM
下次启动时才会生效；当前尚未实现热类卸载。

`PluginContext` 向插件提供以下能力：

-   插件配置。
-   插件日志。
-   Instrumentation。
-   资源注册。
-   同步进程内事件总线。
-   通用扩展注册表。

事件处理器之间相互隔离。插件停止时，会移除该插件注册的事件订阅和扩展。

## 当前限制

-   已在 JDK 8u511 上验证 Java 8 兼容性；尚未对其他 Java 8 发行商和 JVM
    实现进行单独测试。
-   插件依赖版本仅支持精确版本、`*` 和 `2.x`
    这类主版本前缀；暂不支持通用语义化版本范围表达式。
-   配置使用 Java Properties 格式加载。配置热更新需要插件通过实现
    `ConfigurablePlugin` 主动选择支持；管理 API
    尚未实现配置内容查看功能。
-   事件投递采用同步方式，尚无队列或背压控制。
-   管理 API 仅绑定本地回环地址并使用 Token
    保护，但尚未提供按命令划分的授权机制，也不支持 Token 轮换。
-   支持通过本地 API/CLI 安装和升级插件。升级会暂存，并在下一次 JVM
    启动时生效；尚未实现热类卸载。
