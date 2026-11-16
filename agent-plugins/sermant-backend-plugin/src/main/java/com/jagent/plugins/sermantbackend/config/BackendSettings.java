package com.jagent.plugins.sermantbackend.config;

import com.jagent.api.PluginConfiguration;

public final class BackendSettings {
    private final boolean enabled;
    private final String address;
    private final int port;
    private final int connectTimeoutMs;
    private final int heartbeatIntervalMs;
    private final int eventIntervalMs;
    private final int reconnectIntervalMs;
    private final String serviceName;
    private final String appName;
    private final String appType;
    private final String version;
    private final boolean eventsEnabled;
    private final boolean reportWarn;
    private final boolean reportError;
    private final int maxQueueSize;
    private final long dedupeIntervalMs;

    private BackendSettings(PluginConfiguration config) {
        enabled = bool(config, "sermant.backend.enabled", true);
        address = value(config, "sermant.backend.address", "127.0.0.1");
        port = integer(config, "sermant.backend.port", 6888, 1, 65535);
        connectTimeoutMs = integer(config, "sermant.backend.connect-timeout-ms", 5000, 100, 120000);
        heartbeatIntervalMs = integer(config, "sermant.backend.heartbeat-interval-ms", 30000, 1000, 3600000);
        eventIntervalMs = integer(config, "sermant.backend.event-interval-ms", 30000, 1000, 3600000);
        reconnectIntervalMs = integer(config, "sermant.backend.reconnect-interval-ms", 5000, 250, 300000);
        serviceName = value(config, "sermant.agent.service-name", "");
        appName = value(config, "sermant.agent.app-name", "");
        appType = value(config, "sermant.agent.app-type", "JAVA");
        version = value(config, "sermant.agent.version", "0.1.0");
        eventsEnabled = bool(config, "sermant.event.enabled", true);
        reportWarn = bool(config, "sermant.event.report-warn", true);
        reportError = bool(config, "sermant.event.report-error", true);
        maxQueueSize = integer(config, "sermant.event.max-queue-size", 1000, 10, 100000);
        dedupeIntervalMs = longValue(config, "sermant.event.dedupe-interval-ms", 300000, 0, 86400000);
    }

    public static BackendSettings from(PluginConfiguration config) {
        return new BackendSettings(config);
    }

    private static String value(PluginConfiguration config, String key, String fallback) {
        String value = config.get(key);
        return value == null ? fallback : value.trim();
    }

    private static boolean bool(PluginConfiguration config, String key, boolean fallback) {
        String value = config.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(PluginConfiguration config, String key, int fallback, int min, int max) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static long longValue(PluginConfiguration config, String key, long fallback, long min, long max) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getAddress() { return address; }
    public int getPort() { return port; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public int getEventIntervalMs() { return eventIntervalMs; }
    public int getReconnectIntervalMs() { return reconnectIntervalMs; }
    public String getServiceName() { return serviceName; }
    public String getAppName() { return appName; }
    public String getAppType() { return appType; }
    public String getVersion() { return version; }
    public boolean isEventsEnabled() { return eventsEnabled; }
    public boolean isReportWarn() { return reportWarn; }
    public boolean isReportError() { return reportError; }
    public int getMaxQueueSize() { return maxQueueSize; }
    public long getDedupeIntervalMs() { return dedupeIntervalMs; }
}
