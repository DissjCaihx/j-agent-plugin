package com.jagent.bootstrap;

import com.jagent.runtime.manager.PluginRuntime;
import com.jagent.runtime.management.LocalManagementServer;
import com.jagent.runtime.logging.RuntimeLog;

import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.nio.file.Path;

public final class AgentBootstrap {
    private static boolean initialized;

    private AgentBootstrap() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        initialize(args, instrumentation, "premain");
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        initialize(args, instrumentation, "agentmain");
    }

    private static synchronized void initialize(String args, Instrumentation instrumentation, String source) {
        if (initialized) {
            RuntimeLog.warn("bootstrap", "Agent already initialized; ignoring repeated " + source + " invocation");
            return;
        }
        RuntimeLog.configureFile(resolveAgentDirectory());
        Path directory = resolvePluginDirectory(args);
        RuntimeLog.info("bootstrap", "Initializing via " + source + "; plugin directory=" + directory);
        PluginRuntime runtime = new PluginRuntime(instrumentation);
        runtime.discoverAndStart(directory, resolveAgentConfiguration());
        try {
            int port = Integer.getInteger("jagent.management.port", 0);
            if (port > 0) {
                String token = System.getProperty("jagent.management.token");
                final LocalManagementServer management = new LocalManagementServer(runtime, token);
                management.start(port);
                RuntimeLog.info("management", "Local management API listening on 127.0.0.1:" + management.getPort());
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    public void run() {
                        management.close();
                    }
                }, "j-agent-management-shutdown"));
            }
        } catch (Exception e) {
            RuntimeLog.error("management", "Local management server failed", e);
        }
        initialized = true;
    }

    static Path resolvePluginDirectory(String args) {
        if (args != null && !args.trim().isEmpty()) return Paths.get(args.trim()).toAbsolutePath().normalize();
        Path agentDirectory = resolveAgentDirectory();
        if (agentDirectory != null) return agentDirectory.resolve("plugins").toAbsolutePath().normalize();
        return Paths.get("plugins").toAbsolutePath().normalize();
    }

    private static Path resolveAgentConfiguration() {
        String configured = System.getProperty("jagent.config");
        if (configured != null && !configured.trim().isEmpty())
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        Path agentDirectory = resolveAgentDirectory();
        return agentDirectory == null ? null : agentDirectory.resolve("agent.yaml");
    }

    private static Path resolveAgentDirectory() {
        try {
            Path agentJar = Paths.get(AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return java.nio.file.Files.isDirectory(agentJar) ? agentJar : agentJar.getParent();
        } catch (URISyntaxException | RuntimeException e) {
            RuntimeLog.error("bootstrap", "Cannot resolve agent location", e);
            return null;
        }
    }
}
