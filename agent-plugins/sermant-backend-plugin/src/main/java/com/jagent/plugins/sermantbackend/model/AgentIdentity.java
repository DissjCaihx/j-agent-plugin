package com.jagent.plugins.sermantbackend.model;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

public final class AgentIdentity {
    private volatile String serviceName;
    private volatile String appName;
    private final boolean serviceConfigured;
    private final String appType;
    private final String version;
    private final String hostName;
    private final List<String> ips;
    private final String processId;
    private final String instanceId;

    public AgentIdentity(String configuredService, String configuredApp, String appType, String version) {
        String detectedApp = first(configuredApp, System.getProperty("sermant.appName"),
                System.getProperty("spring.application.name"), mainArtifact());
        String detectedService = first(configuredService, System.getProperty("sermant.service"), detectedApp);
        this.serviceConfigured = !empty(configuredService) || !empty(System.getProperty("sermant.service"));
        this.appName = detectedApp;
        this.serviceName = detectedService;
        this.appType = empty(appType) ? "JAVA" : appType;
        this.version = version;
        this.hostName = hostName();
        this.ips = localIps();
        this.processId = processId();
        this.instanceId = hostName + "-" + processId + "-" + UUID.randomUUID().toString();
    }

    public void updateApplicationName(String name) {
        if (!empty(name)) {
            appName = name.trim();
            if (!serviceConfigured) serviceName = name.trim();
        }
    }

    private static String mainArtifact() {
        String command = System.getProperty("sun.java.command", "application");
        String first = command.split("\\s+", 2)[0];
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        if (slash >= 0) first = first.substring(slash + 1);
        if (first.toLowerCase().endsWith(".jar")) first = first.substring(0, first.length() - 4);
        return empty(first) ? "application" : first;
    }

    private static String processId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int at = runtimeName.indexOf('@');
        return at < 0 ? runtimeName : runtimeName.substring(0, at);
    }

    private static String hostName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return "unknown-host"; }
    }

    private static List<String> localIps() {
        List<String> result = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && !address.isAnyLocalAddress()) result.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        if (result.isEmpty()) result.add("127.0.0.1");
        return Collections.unmodifiableList(result);
    }

    private static String first(String... candidates) {
        for (String candidate : candidates) if (!empty(candidate)) return candidate.trim();
        return "application";
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    public String getServiceName() { return serviceName; }
    public String getAppName() { return appName; }
    public String getAppType() { return appType; }
    public String getVersion() { return version; }
    public String getHostName() { return hostName; }
    public List<String> getIps() { return ips; }
    public String getProcessId() { return processId; }
    public String getInstanceId() { return instanceId; }
}
