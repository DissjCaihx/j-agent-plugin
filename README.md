# j-agent-plugin

Java 8 compatible Java Agent plugin runtime. Core owns plugin discovery, isolated class loading, lifecycle, resource cleanup and local management. Functional capabilities belong in plugins.

## Build

```sh
mvn clean verify
```

The `agent-bootstrap` package produces an executable agent JAR with runtime dependencies and the `Premain-Class` manifest. The CLI is built in `agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar`.

`mvn clean verify` creates `j-agent-plugin/` at the repository root and a ZIP at `agent-distribution/target/agent-distribution-0.1.0-SNAPSHOT.zip`. Both contain `agent.jar`, `agent.yaml`, and a ready-to-scan `plugins/` directory. Native plugins live under `agent-plugins/`; `agent-plugin-bundle` stages each plugin JAR and descriptor into the distribution layout during `package`.

## Use the Spring Boot Nacos plugin

Build the distribution and copy the example Nacos properties file:

```sh
mvn clean package
cd j-agent-plugin
cp plugins/springboot-nacos/conf/config.properties.example \
  plugins/springboot-nacos/conf/config.properties
```

Edit `plugins/springboot-nacos/conf/config.properties`. For the Nacos environment shown in this project, the connection and namespace settings can be:

```properties
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

`GBM-Space` is the namespace ID shown for “GBM中台服务”; for “GBM网关”, use its namespace ID `4fb59a5e-0999-4013-bff2-a8ad525e96ef`. Use a DataId that exists in the selected namespace, or set `nacos.config.enabled=false` if this application does not need Nacos Config.

Set the credentials in the shell without putting the password in the command history:

```sh
export NACOS_USERNAME=nacos
read -s NACOS_PASSWORD
export NACOS_PASSWORD
```

The global `agent.yaml` already enables `springboot-nacos`. Start the Spring Boot application with the agent **before Spring starts**:

```sh
java -javaagent:/path/to/j-agent-plugin/agent.jar \
  -jar /path/to/your-spring-boot-app.jar
```

The plugin reads the actual bound port from the Spring Boot WebServer after it starts. It gets the service name from `spring.cloud.nacos.discovery.service` or `spring.application.name`, then reads group, cluster, weight, ephemeral and metadata settings from the Spring Environment. Set the advertised IP with `spring.cloud.nacos.discovery.ip` when available, or `nacos.discovery.ip` in the plugin properties. If neither is set, a non-wildcard `server.address` is used; when the app binds to `0.0.0.0` or `::`, configure an advertised IP explicitly. On context shutdown, the registered instance is deregistered.

To use the local management API, add these JVM options and choose your own token:

```sh
java -Djagent.management.port=8787 \
  -Djagent.management.token=change-me \
  -javaagent:/path/to/j-agent-plugin/agent.jar \
  -jar /path/to/your-spring-boot-app.jar
```

Then use the CLI from the project checkout:

```sh
java -Djagent.url=http://127.0.0.1:8787 \
  -Djagent.token=change-me \
  -jar /path/to/j-agent-plugin/agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar list
```

Use `list`, `start <plugin-id>`, `stop <plugin-id>`, and `reload-config <plugin-id>` to inspect and manage plugins. Plugin event-bus requests and Nacos change notifications are for communication between Agent plugins; they do not automatically refresh the application's Spring Environment or beans.

## Run the example

Build the project, then use the bundled plugin directory directly:

```sh
java -Djagent.management.port=8787 -Djagent.management.token=change-me \
  -javaagent:j-agent-plugin/agent.jar \
  -jar your-application.jar
```

Without an agent argument, the scanner uses the `plugins/` directory beside `agent.jar`. To use another directory, pass it as `-javaagent:/path/to/agent.jar=/path/to/plugins`.

The adjacent `agent.yaml` controls automatic startup. The packaged default enables `runtime-info` and `springboot-nacos`:

```yaml
plugins:
  enabled:
    - runtime-info
    - springboot-nacos
```

`springboot-nacos` provides Nacos service registration/discovery and config fetch/listen through the plugin's isolated Nacos 2.5.5 client. The distribution includes its private client dependencies under `plugins/springboot-nacos/lib/`. Copy `plugins/springboot-nacos/conf/config.properties.example` to `config.properties`, configure `nacos.server-addr`, enable the desired capabilities and set credentials through `NACOS_USERNAME` / `NACOS_PASSWORD`. When registration is enabled, the plugin gets the actual port and service information from Spring Boot after the WebServer starts; it does not use a guessed port.

The plugin uses the shared event bus with `java.util.Map` messages so other plugins can interoperate across isolated class loaders. Publish a map with `action=springboot-nacos.discovery.query|subscribe|unsubscribe` and `serviceName` (optional `groupName`), or `action=springboot-nacos.config.get|subscribe|unsubscribe` with `dataId` (optional `group`). The plugin writes `success` and response fields back into the same map. Change notifications are published as `type=springboot-nacos.discovery.changed` or `type=springboot-nacos.config.changed`; config change content is carried in the `content` field and is never written to logs. Subscribe to `Map.class` and filter on `type` to receive change notifications.

## Use the Sermant Backend integration plugin

The `sermant-backend` plugin sends Sermant-compatible heartbeats and WARN/ERROR log events directly to the Backend Netty gateway. It does not use the Backend web UI port (usually `8900`). Enable the Backend gateway and allow the agent host to reach its Netty port (default `6888`), then copy and edit the plugin configuration:

```sh
cp plugins/sermant-backend/conf/config.properties.example \
  plugins/sermant-backend/conf/config.properties
```

Set `sermant.backend.address` and `sermant.backend.port` to the Backend gateway address. The plugin reads `spring.application.name` from a running Spring Boot context when available; for non-Spring applications, set `sermant.agent.service-name` and `sermant.agent.app-name`. Add `sermant-backend` to the `plugins.enabled` list in `agent.yaml` to start it. The current implementation reports heartbeat identity and WARN/ERROR log events; it does not ship complete log files or host/JVM metrics to Sermant Backend.

The transport follows Sermant's Netty gateway wire format: a length-delimited protobuf envelope containing a Gzip-compressed JSON heartbeat or event payload. The plugin uses a bounded log event queue, deduplicates repeated messages for the configured interval, retries gateway connections, and removes its JUL/Logback capture hooks when stopped.

Add plugin IDs to `plugins.enabled` to start them; an empty list starts none. Required dependencies of selected plugins start automatically. Other discovered plugins are loaded and initialized but their `start()` callback is skipped. Override the file with `-Djagent.config=/path/to/agent.yaml`. If no config file exists, each plugin's `lifecycle.auto-start` value is used.

Runtime logs include timestamp, level, thread, component/plugin ID and exception stack traces. Set `logging.level` in `agent.yaml` (`SEVERE`, `WARNING`, `INFO`, `FINE`, `FINER`, or `FINEST`), or override it with `-Djagent.log.level=FINE`. The system property takes precedence.

Agent logs are also written separately from application logs to `<agent-directory>/logs/j-agent-plugin.0.log`, rolling at 10 MiB with five files retained. View them with `tail -f j-agent-plugin/logs/j-agent-plugin.0.log`. Override the directory with `-Djagent.log.dir=/path/to/logs`. If the Agent directory is not writable, logs fall back to `~/.j-agent-plugin/logs/`. Nacos client initialization, Spring context values, hook failures, registration errors and stack traces are recorded there; passwords and tokens are redacted. The same records remain on the console.

The example plugin remains available as a separate module under `examples/hello-plugin` and is not included in the native plugin set.

The management server binds only to `127.0.0.1`. Leave `jagent.management.port` unset to disable it. A non-empty token is required whenever the server is enabled. Every API request must send it in `X-Agent-Token`.

The same agent JAR can be attached to a running JVM with the JDK Attach API:

```java
VirtualMachine vm = VirtualMachine.attach(targetPid);
try {
    vm.loadAgent("/path/to/agent-bootstrap-0.1.0-SNAPSHOT.jar", "/path/to/plugins");
} finally {
    vm.detach();
}
```

The agent manifest declares both `Premain-Class` and `Agent-Class`. Both entry points share idempotent initialization, so a repeated attach in the same JVM is ignored.

```sh
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar list
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar start hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar stop hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar reload-config hello-plugin
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar install /path/to/plugin.zip
java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=change-me -jar agent-cli/target/agent-cli-0.1.0-SNAPSHOT.jar update hello-plugin /path/to/plugin.zip
```

HTTP endpoints: `GET /plugins`, `GET /plugins/{id}`, `POST /plugins/{id}/start`, `POST /plugins/{id}/stop`, `POST /plugins/{id}/config/reload`, `POST /plugins/install`, `POST /plugins/{id}/update` (ZIP body, 128 MiB maximum).

## Plugin layout

```text
plugins/<id>/
├── plugin.yaml
├── plugin.jar
├── lib/                 # optional private dependencies
└── conf/config.properties
```

`plugin.yaml` may declare plugin dependencies using `dependencies.plugins`, each with an `id` and a version (`*`, exact version, or major-prefix such as `2.x`). The runtime validates dependency presence and version, loads in dependency order, and rejects cycles. A dependency must be `ACTIVE` before its consumer starts; stopping a provider with active consumers is rejected.

Install archives must contain `plugin.yaml` and one or more plugin JARs at the archive root or under `lib/`. Uploads are limited to 128 MiB and are checked for ZIP path traversal, entry count, expanded size, descriptor validity and entry class compatibility. Existing plugin upgrades are staged under `.pending/`, preserve the current installation until JVM restart, then move the previous version under `.backups/`.

Plugin configuration is read from `conf/config.properties` during initialization. A plugin opts into hot reload by implementing `ConfigurablePlugin`; the reload callback receives a candidate configuration, which becomes current only when the callback succeeds.

Each plugin gets a child-first class loader. JDK, Agent API, and Runtime types are parent-first to preserve shared type identity and keep Runtime internals out of plugin copies. Class-loader isolation is not a security sandbox. Plugin stop removes registered transformers and managed resources; it cannot promise restoration of bytecode already transformed. Plugin install and upgrade take effect after restarting the target JVM.

`PluginContext` exposes plugin configuration, logging, instrumentation, resource registration, the synchronous in-process event bus and generic extension registry. Event handlers are isolated from each other; plugin stop removes its subscriptions and extensions.

## Current limits

- Java 8 compatibility is verified on JDK 8u511. Other Java 8 vendors and JVM implementations have not been separately tested.
- Plugin dependencies support exact versions, `*`, and major prefixes such as `2.x`; general semantic-version range expressions are not supported.
- Configuration is loaded from Java properties. Hot reload is opt-in via `ConfigurablePlugin`; management API configuration inspection is not implemented.
- Event delivery is synchronous and has no queue/backpressure controls.
- The management API is token protected and loopback only, but does not provide per-command authorization or token rotation.
- Install and upgrade are supported through the local API/CLI. Upgrades are staged and activate on the next JVM start; hot class unloading is not implemented.
