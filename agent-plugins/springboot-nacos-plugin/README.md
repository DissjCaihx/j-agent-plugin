# springboot-nacos-plugin

Pure Java Nacos integration plugin for Java 8. The Nacos 2.5.5 client and its runtime libraries are staged in the plugin's `lib/` directory and loaded by the plugin class loader. Spring Boot is not required.

## Configure

Copy `conf/config.properties.example` from the distribution to `conf/config.properties`. Set `nacos.server-addr`, namespace and credentials. Credentials can use `${ENVIRONMENT_VARIABLE}` placeholders; unresolved values are treated as empty. Keep production credentials in environment variables or a protected local file.

The plugin is idle until `nacos.enabled=true`. `nacos.discovery.enabled` and `nacos.config.enabled` independently enable Naming and Config. When `nacos.discovery.register.enabled=true`, an optional bytecode hook observes Spring Boot servlet/reactive web-server lifecycle without adding a Spring dependency. After the server binds, it reads the actual port from the context's WebServer and reads service name/group/cluster/weight/ephemeral/metadata from the Spring Environment (`spring.application.name` and `spring.cloud.nacos.discovery.*`). Advertised IP comes from `spring.cloud.nacos.discovery.ip`, then `nacos.discovery.ip`, then a non-wildcard `server.address`; if the context only binds a wildcard address, registration is skipped with an explicit log and the advertised IP must be configured. Context shutdown deregisters the same instance. Config startup loads the configured DataIds; the Nacos SDK handles later transient connection recovery.

## Event-bus protocol

Use `PluginContext.getEventBus()` and subscribe to `Map.class`. Publish request maps using the following `action` values:

| Action | Request fields | Response fields |
| --- | --- | --- |
| `springboot-nacos.discovery.query` | `serviceName`, optional `groupName` | `success`, `instances` or `error` |
| `springboot-nacos.discovery.subscribe` / `unsubscribe` | `serviceName`, optional `groupName` | `success` or `error` |
| `springboot-nacos.config.get` | `dataId`, optional `group` | `success`, `content` or `error` |
| `springboot-nacos.config.subscribe` / `unsubscribe` | `dataId`, optional `group` | `success` or `error` |

The event bus is synchronous; a request map is updated before `publish()` returns. Discovery notifications use `type=springboot-nacos.discovery.changed` with `serviceName`, `groupName` and `instances`. Config notifications use `type=springboot-nacos.config.changed` with `dataId`, `group` and `content`. Config contents and credentials are excluded from logs.

Stopping the plugin removes listeners, deregisters instances captured from active Spring Boot contexts, and shuts down both Nacos clients. Load the Agent before Spring Boot starts so the lifecycle hook can capture the actual WebServer port. Nacos SDK reconnection is used for transient outages; authentication/configuration failures are reported without retry loops. Config change notifications do not refresh Spring's Environment or application beans.

Agent diagnostics are written separately to `<agent-directory>/logs/j-agent-plugin.0.log` (10 MiB rolling files, five retained). Set `-Djagent.log.dir=/path/to/logs` to choose another directory. Look there for missing/invalid Spring properties, Nacos authentication or connection exceptions, and registration failures. Credentials are redacted.

