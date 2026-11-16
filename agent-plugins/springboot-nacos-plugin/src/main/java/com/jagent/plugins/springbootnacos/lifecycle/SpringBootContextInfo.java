package com.jagent.plugins.springbootnacos.lifecycle;

import com.jagent.plugins.springbootnacos.config.NacosSettings;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reflects the running Spring context without adding a Spring compile or runtime dependency.
 */
public final class SpringBootContextInfo {
    private static final String METADATA_PREFIX = "spring.cloud.nacos.discovery.metadata.";

    private final String serviceName;
    private final String groupName;
    private final String clusterName;
    private final String ip;
    private final int port;
    private final double weight;
    private final boolean ephemeral;
    private final Map<String, String> metadata;

    private SpringBootContextInfo(String serviceName, String groupName, String clusterName,
                                  String ip, int port, double weight, boolean ephemeral,
                                  Map<String, String> metadata) {
        this.serviceName = serviceName;
        this.groupName = groupName;
        this.clusterName = clusterName;
        this.ip = ip;
        this.port = port;
        this.weight = weight;
        this.ephemeral = ephemeral;
        this.metadata = metadata;
    }

    public static SpringBootContextInfo read(Object context, NacosSettings settings) throws Exception {
        Object environment = invoke(context, "getEnvironment");
        Object webServer = invoke(context, "getWebServer");
        int port = ((Number) invoke(webServer, "getPort")).intValue();
        if (port < 1 || port > 65535)
            throw new IllegalStateException("Spring Boot reported an invalid bound web port: " + port);

        String serviceName = first(property(environment, "spring.cloud.nacos.discovery.service"),
                first(property(environment, "spring.application.name"), settings.getServiceName()));
        if (serviceName == null || serviceName.trim().isEmpty())
            throw new IllegalStateException("Spring Environment has no spring.application.name; set spring.cloud.nacos.discovery.service or spring.application.name");

        String configuredIp = first(property(environment, "spring.cloud.nacos.discovery.ip"), settings.getIp());
        String serverAddress = property(environment, "server.address");
        String ip = resolveIp(configuredIp, serverAddress);
        String group = first(property(environment, "spring.cloud.nacos.discovery.group"), settings.getGroupName());
        String cluster = first(property(environment, "spring.cloud.nacos.discovery.cluster-name"), settings.getClusterName());
        double weight = number(first(property(environment, "spring.cloud.nacos.discovery.weight"), null), settings.getWeight());
        boolean ephemeral = bool(property(environment, "spring.cloud.nacos.discovery.ephemeral"), settings.isEphemeral());
        Map<String, String> metadata = new LinkedHashMap<String, String>(settings.getMetadata());
        metadata.putAll(metadata(environment));
        return new SpringBootContextInfo(serviceName.trim(), group, cluster, ip, port, weight, ephemeral, metadata);
    }

    public static String springBootVersion(Object context) throws Exception {
        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> versionClass = Class.forName("org.springframework.boot.SpringBootVersion", false, loader);
        Object version = versionClass.getMethod("getVersion").invoke(null);
        return version == null ? null : String.valueOf(version);
    }

    private static Map<String, String> metadata(Object environment) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        Object sources = invoke(environment, "getPropertySources");
        if (!(sources instanceof Iterable)) return values;
        for (Object source : (Iterable<?>) sources) {
            try {
                Object names = invoke(source, "getPropertyNames");
                if (!(names instanceof String[])) continue;
                for (String name : (String[]) names) {
                    if (name.startsWith(METADATA_PREFIX) && name.length() > METADATA_PREFIX.length()) {
                        String value = property(environment, name);
                        if (value != null) values.put(name.substring(METADATA_PREFIX.length()), value);
                    }
                }
            } catch (Exception ignored) {
                // Non enumerable property sources do not expose metadata keys.
            }
        }
        return values;
    }

    private static String resolveIp(String configured, String serverAddress) throws Exception {
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        if (serverAddress != null && !serverAddress.trim().isEmpty()) {
            String bound = serverAddress.trim();
            if (!"0.0.0.0".equals(bound) && !"::".equals(bound) && !"0:0:0:0:0:0:0:0".equals(bound)) return bound;
        }
        throw new IllegalStateException("Cannot infer an advertised IP from a wildcard server.address; set spring.cloud.nacos.discovery.ip or nacos.discovery.ip");
    }

    private static String property(Object environment, String key) throws Exception {
        Object value = invoke(environment, "getProperty", new Class<?>[]{String.class}, new Object[]{key});
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Object invoke(Object target, String method) throws Exception {
        if (target == null) throw new IllegalStateException("Spring Boot context did not expose " + method + "()");
        return invoke(target, method, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object[] args) throws Exception {
        Method found = target.getClass().getMethod(method, types);
        if (!found.isAccessible()) found.setAccessible(true);
        return found.invoke(target, args);
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred.trim();
    }

    private static double number(String value, double fallback) {
        return value == null ? fallback : Double.parseDouble(value);
    }

    private static boolean bool(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public Map<String, String> getMetadata() {
        return new LinkedHashMap<String, String>(metadata);
    }
}
