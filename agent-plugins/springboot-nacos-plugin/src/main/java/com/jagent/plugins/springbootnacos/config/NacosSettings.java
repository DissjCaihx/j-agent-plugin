package com.jagent.plugins.springbootnacos.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.jagent.api.PluginConfiguration;

import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NacosSettings {
    private final Properties clientProperties;
    private final boolean enabled;
    private final boolean configEnabled;
    private final boolean discoveryEnabled;
    private final boolean registerEnabled;
    private final String serviceName;
    private final String groupName;
    private final String clusterName;
    private final String ip;
    private final float weight;
    private final boolean ephemeral;
    private final String[] subscribedServices;
    private final String[] configDataIds;
    private final String configGroup;
    private final long timeoutMs;
    private final Map<String, String> metadata;

    private NacosSettings(Properties p) {
        clientProperties = p;
        enabled = bool(p, "enabled", false);
        configEnabled = bool(p, "config.enabled", false);
        discoveryEnabled = bool(p, "discovery.enabled", false);
        registerEnabled = bool(p, "discovery.register.enabled", false);
        serviceName = value(p, "discovery.service-name", "");
        groupName = value(p, "discovery.group-name", "DEFAULT_GROUP");
        clusterName = value(p, "discovery.cluster-name", "DEFAULT");
        ip = value(p, "discovery.ip", "");
        weight = decimal(p, "discovery.weight", 1.0f);
        ephemeral = bool(p, "discovery.ephemeral", true);
        subscribedServices = split(value(p, "discovery.subscribe", ""));
        configDataIds = split(value(p, "config.data-ids", value(p, "config.data-id", "")));
        configGroup = value(p, "config.group", "DEFAULT_GROUP");
        timeoutMs = longValue(p, "connection.timeout-ms", 5000L);
        metadata = new LinkedHashMap<String, String>();
        for (String key : split(value(p, "discovery.metadata.keys", ""))) {
            String value = p.getProperty("nacos.discovery.metadata." + key);
            if (value != null) metadata.put(key, value);
        }

        if (enabled) {
            required(p, "server-addr");
            if (!configEnabled && !discoveryEnabled)
                throw new IllegalArgumentException("enable config.enabled or discovery.enabled");
            if (configEnabled && configDataIds.length == 0)
                throw new IllegalArgumentException("config.data-id(s) is required when config.enabled=true");
        }
    }

    public static NacosSettings from(PluginConfiguration source) {
        Properties p = new Properties();
        String[] keys = {"enabled", "config.enabled", "config.data-id", "config.data-ids", "config.group",
                "discovery.enabled", "discovery.register.enabled", "discovery.service-name", "discovery.group-name",
                "discovery.cluster-name", "discovery.ip", "discovery.weight", "discovery.ephemeral",
                "discovery.subscribe", "discovery.metadata.keys", "connection.timeout-ms"};
        for (String key : keys) {
            String raw = source.get("nacos." + key);
            if (raw != null) p.setProperty("nacos." + key, expand(raw));
        }
        String metadataKeys = source.get("nacos.discovery.metadata.keys");
        for (String key : split(metadataKeys)) {
            String value = source.get("nacos.discovery.metadata." + key);
            if (value != null) p.setProperty("nacos.discovery.metadata." + key, expand(value));
        }
        copy(source, p, "server-addr", PropertyKeyConst.SERVER_ADDR);
        copy(source, p, "namespace", PropertyKeyConst.NAMESPACE);
        copy(source, p, "username", PropertyKeyConst.USERNAME);
        copy(source, p, "password", PropertyKeyConst.PASSWORD);
        return new NacosSettings(p);
    }

    private static void copy(PluginConfiguration source, Properties p, String key, String target) {
        String value = expand(source.get("nacos." + key));
        if (value != null && !value.trim().isEmpty()) p.setProperty(target, value.trim());
        String option = source.get("nacos." + key);
        if (option != null) p.setProperty(key, expand(option).trim());
    }

    private static String expand(String value) {
        if (value == null) return null;
        String result = value.trim();
        if (result.startsWith("${") && result.endsWith("}")) {
            String name = result.substring(2, result.length() - 1);
            String fromEnv = System.getenv(name);
            return fromEnv != null ? fromEnv : System.getProperty(name, "");
        }
        return result;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("nacos." + key + " is required");
        return value.trim();
    }

    private static String value(Properties p, String key, String fallback) {
        String v = p.getProperty("nacos." + key);
        return v == null ? fallback : v.trim();
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty("nacos." + key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static long longValue(Properties p, String key, long fallback) {
        String v = p.getProperty("nacos." + key);
        return v == null ? fallback : Long.parseLong(v.trim());
    }

    private static float decimal(Properties p, String key, float fallback) {
        String v = p.getProperty("nacos." + key);
        return v == null ? fallback : Float.parseFloat(v.trim());
    }

    private static String[] split(String value) {
        if (value == null || value.trim().isEmpty()) return new String[0];
        String[] values = value.split(",");
        for (int i = 0; i < values.length; i++) values[i] = values[i].trim();
        return values;
    }

    public Properties getClientProperties() {
        return (Properties) clientProperties.clone();
    }

    public String getServerAddress() {
        return clientProperties.getProperty(PropertyKeyConst.SERVER_ADDR, "<unset>");
    }

    public boolean hasUsername() {
        return clientProperties.containsKey(PropertyKeyConst.USERNAME);
    }

    public boolean hasPassword() {
        return clientProperties.containsKey(PropertyKeyConst.PASSWORD);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigEnabled() {
        return configEnabled;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public boolean isRegisterEnabled() {
        return registerEnabled;
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

    public float getWeight() {
        return weight;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public String[] getSubscribedServices() {
        return subscribedServices.clone();
    }

    public String[] getConfigDataIds() {
        return configDataIds.clone();
    }

    public String getConfigGroup() {
        return configGroup;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public Map<String, String> getMetadata() {
        return new LinkedHashMap<String, String>(metadata);
    }

    public String getNamespace() {
        return clientProperties.getProperty(PropertyKeyConst.NAMESPACE, "");
    }
}
