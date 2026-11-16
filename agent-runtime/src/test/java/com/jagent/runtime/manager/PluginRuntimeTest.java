package com.jagent.runtime.manager;

import com.jagent.api.*;
import com.jagent.api.configuration.ConfigurablePlugin;
import com.jagent.runtime.lifecycle.PluginState;
import org.junit.Test;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.Assert.*;

public class PluginRuntimeTest {
    private static final AtomicInteger concurrentStarts = new AtomicInteger();
    private static final AtomicInteger resourceCloses = new AtomicInteger();
    private static volatile boolean dependencyObserved;

    @Test
    public void discoversStartsStopsAndRejectsRestart() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins");
        Path pluginDir = Files.createDirectory(root.resolve("test-plugin"));
        writeDescriptor(pluginDir, "test-plugin", TestPlugin.class.getName());
        writePluginJar(pluginDir);

        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        PluginInfo info = runtime.getPlugin("test-plugin");
        assertEquals(PluginState.ACTIVE, info.getState());
        assertEquals(1, runtime.plugins().size());
        assertSame(info.getState(), runtime.startPlugin("test-plugin").getState());
        assertEquals(PluginState.STOPPED, runtime.stopPlugin("test-plugin").getState());
        try {
            runtime.startPlugin("test-plugin");
            fail("Stopped plugin must require JVM restart");
        } catch (IllegalStateException expected) { }
    }

    @Test
    public void unknownPluginIsReported() {
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        try { runtime.getPlugin("missing"); fail("Expected unknown plugin error"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("missing")); }
    }

    @Test
    public void loadFailureIsVisibleAsFailedState() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-failed");
        Path pluginDir = Files.createDirectory(root.resolve("broken-plugin"));
        writeDescriptor(pluginDir, "broken-plugin", "missing.PluginEntry");
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        PluginInfo info = runtime.getPlugin("broken-plugin");
        assertEquals(PluginState.FAILED, info.getState());
        assertNotNull(info.getFailure());
    }

    @Test
    public void stopTimeoutDoesNotBlockManagerAndIsReported() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-timeout");
        Path pluginDir = Files.createDirectory(root.resolve("slow-plugin"));
        writeDescriptor(pluginDir, "slow-plugin", SlowStopPlugin.class.getName());
        writePluginJar(pluginDir, SlowStopPlugin.class);
        String oldTimeout = System.getProperty("jagent.plugin.stop.timeoutMillis");
        System.setProperty("jagent.plugin.stop.timeoutMillis", "30");
        try {
            PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
            runtime.discoverAndStart(root);
            PluginInfo info = runtime.stopPlugin("slow-plugin");
            assertEquals(PluginState.STOPPED, info.getState());
            assertTrue(info.getFailure().contains("timed out"));
        } finally {
            if (oldTimeout == null) System.clearProperty("jagent.plugin.stop.timeoutMillis");
            else System.setProperty("jagent.plugin.stop.timeoutMillis", oldTimeout);
        }
    }

    @Test
    public void transformerIsRemovedWhenPluginStops() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-transformer");
        Path pluginDir = Files.createDirectory(root.resolve("transform-plugin"));
        writeDescriptor(pluginDir, "transform-plugin", TransformerPlugin.class.getName());
        writePluginJar(pluginDir, TransformerPlugin.class);
        final AtomicInteger additions = new AtomicInteger();
        final AtomicInteger removals = new AtomicInteger();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Instrumentation.class }, (proxy, method, args) -> {
                    if ("addTransformer".equals(method.getName())) additions.incrementAndGet();
                    if ("removeTransformer".equals(method.getName())) { removals.incrementAndGet(); return true; }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        PluginRuntime runtime = new PluginRuntime(instrumentation);
        runtime.discoverAndStart(root);
        assertEquals(1, additions.get());
        runtime.stopPlugin("transform-plugin");
        assertEquals(1, removals.get());
    }

    @Test
    public void configurationReloadIsOptInAndCallbackControlsCommit() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-config");
        Path pluginDir = Files.createDirectory(root.resolve("config-plugin"));
        Path conf = Files.createDirectory(pluginDir.resolve("conf"));
        Files.write(conf.resolve("config.properties"), "mode=initial\n".getBytes(StandardCharsets.UTF_8));
        writeDescriptor(pluginDir, "config-plugin", ReloadableTestPlugin.class.getName());
        writePluginJar(pluginDir, ReloadableTestPlugin.class);
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        Files.write(conf.resolve("config.properties"), "mode=accepted\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(PluginState.ACTIVE, runtime.reloadPluginConfiguration("config-plugin").getState());
        Files.write(conf.resolve("config.properties"), "mode=rejected\n".getBytes(StandardCharsets.UTF_8));
        try { runtime.reloadPluginConfiguration("config-plugin"); fail("Plugin should reject invalid config"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Configuration reload failed")); }
    }

    @Test
    public void startsDependenciesBeforeConsumersAndReportsInvalidGraphs() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-dependencies");
        Path consumer = Files.createDirectory(root.resolve("consumer"));
        Path provider = Files.createDirectory(root.resolve("provider"));
        writeDescriptorWithDependencies(provider, "provider", ProviderPlugin.class.getName(), null);
        writeDescriptorWithDependencies(consumer, "consumer", ConsumerPlugin.class.getName(), "  plugins:\n    - id: provider\n      version: 1.x\n");
        writePluginJar(provider, ProviderPlugin.class);
        writePluginJar(consumer, ConsumerPlugin.class);
        dependencyObserved = false;
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        assertTrue("Provider extension should exist before consumer start", dependencyObserved);

        Path invalidRoot = Files.createTempDirectory("agent-plugins-invalid-dependencies");
        Path missing = Files.createDirectory(invalidRoot.resolve("missing-consumer"));
        writeDescriptorWithDependencies(missing, "missing-consumer", TestPlugin.class.getName(), "  plugins:\n    - id: absent\n      version: \"*\"\n");
        writePluginJar(missing, TestPlugin.class);
        PluginRuntime invalidRuntime = new PluginRuntime(noopInstrumentation());
        invalidRuntime.discoverAndStart(invalidRoot);
        assertEquals(PluginState.FAILED, invalidRuntime.getPlugin("missing-consumer").getState());
    }

    @Test
    public void rejectsDependencyCycles() throws Exception {
        Path root = Files.createTempDirectory("agent-plugin-cycle");
        Path alpha = Files.createDirectory(root.resolve("alpha"));
        Path beta = Files.createDirectory(root.resolve("beta"));
        writeDescriptorWithDependencies(alpha, "alpha", TestPlugin.class.getName(), "  plugins:\n    - id: beta\n      version: \"*\"\n");
        writeDescriptorWithDependencies(beta, "beta", TestPlugin.class.getName(), "  plugins:\n    - id: alpha\n      version: \"*\"\n");
        writePluginJar(alpha, TestPlugin.class);
        writePluginJar(beta, TestPlugin.class);
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        assertEquals(PluginState.FAILED, runtime.getPlugin("alpha").getState());
        assertEquals(PluginState.FAILED, runtime.getPlugin("beta").getState());
    }

    @Test
    public void concurrentStartIsIdempotentAndResourcesAreClosed() throws Exception {
        Path root = Files.createTempDirectory("agent-plugins-concurrent");
        Path pluginDir = Files.createDirectory(root.resolve("resource-plugin"));
        writeDescriptorWithDependencies(pluginDir, "resource-plugin", ResourcePlugin.class.getName(), null, false);
        writePluginJar(pluginDir, ResourcePlugin.class);
        concurrentStarts.set(0);
        resourceCloses.set(0);
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        Runnable start = new Runnable() {
            public void run() {
                ready.countDown();
                try { go.await(); runtime.startPlugin("resource-plugin"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        Thread first = new Thread(start); Thread second = new Thread(start);
        first.start(); second.start();
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        go.countDown();
        first.join(2000L); second.join(2000L);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(1, concurrentStarts.get());
        runtime.stopPlugin("resource-plugin");
        assertEquals(1, resourceCloses.get());
    }

    @Test
    public void globalEnabledListOverridesDescriptorDefaults() throws Exception {
        Path root = Files.createTempDirectory("agent-plugin-selection");
        Path selected = Files.createDirectory(root.resolve("selected"));
        Path omitted = Files.createDirectory(root.resolve("omitted"));
        writeDescriptorWithDependencies(selected, "selected", ResourcePlugin.class.getName(), null, false);
        writeDescriptorWithDependencies(omitted, "omitted", ResourcePlugin.class.getName(), null, true);
        writePluginJar(selected, ResourcePlugin.class); writePluginJar(omitted, ResourcePlugin.class);
        Path agentConfig = root.resolveSibling("agent-enabled.yaml");
        Files.write(agentConfig, "plugins:\n  enabled:\n    - selected\n".getBytes(StandardCharsets.UTF_8));
        concurrentStarts.set(0);
        PluginRuntime runtime = new PluginRuntime(noopInstrumentation());
        runtime.discoverAndStart(root, agentConfig);
        assertEquals(PluginState.ACTIVE, runtime.getPlugin("selected").getState());
        assertEquals(PluginState.INITIALIZED, runtime.getPlugin("omitted").getState());
        assertEquals(1, concurrentStarts.get());
        runtime.stopPlugin("selected");
        Files.deleteIfExists(agentConfig);
    }

    private static void writeDescriptor(Path dir, String id, String entryClass) throws Exception {
        writeDescriptorWithDependencies(dir, id, entryClass, null);
    }

    private static void writeDescriptorWithDependencies(Path dir, String id, String entryClass, String dependencyYaml) throws Exception {
        writeDescriptorWithDependencies(dir, id, entryClass, dependencyYaml, true);
    }

    private static void writeDescriptorWithDependencies(Path dir, String id, String entryClass, String dependencyYaml, boolean autoStart) throws Exception {
        String yaml = "plugin:\n  id: " + id + "\n  version: 1.0.0\n  entry-class: " + entryClass + "\n";
        if (dependencyYaml != null) yaml += "dependencies:\n" + dependencyYaml;
        yaml += "lifecycle:\n  auto-start: " + autoStart + "\n";
        Files.write(dir.resolve("plugin.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static void writePluginJar(Path dir) throws Exception {
        writePluginJar(dir, TestPlugin.class);
    }

    private static void writePluginJar(Path dir, Class<?> pluginType) throws Exception {
        String resource = "/" + pluginType.getName().replace('.', '/') + ".class";
        try (InputStream input = pluginType.getResourceAsStream(resource);
             JarOutputStream jar = new JarOutputStream(Files.newOutputStream(dir.resolve("plugin.jar")))) {
            assertNotNull(input);
            jar.putNextEntry(new JarEntry(resource.substring(1)));
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) jar.write(buffer, 0, count);
            jar.closeEntry();
        }
    }

    private static Instrumentation noopInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(PluginRuntimeTest.class.getClassLoader(),
                new Class<?>[] { Instrumentation.class }, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }

    public static class TestPlugin implements AgentPlugin {
        public PluginDescriptor descriptor() { return new PluginDescriptor("test-plugin", "Test", "1.0"); }
        public void initialize(PluginContext context) { }
        public void start() { }
        public void stop() { }
    }

    public static class SlowStopPlugin implements AgentPlugin {
        public PluginDescriptor descriptor() { return new PluginDescriptor("slow-plugin", "Slow", "1.0"); }
        public void initialize(PluginContext context) { }
        public void start() { }
        public void stop() throws PluginException {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PluginException("interrupted", e); }
        }
    }

    public static class TransformerPlugin implements AgentPlugin, ClassFileTransformer {
        private PluginContext context;
        public PluginDescriptor descriptor() { return new PluginDescriptor("transform-plugin", "Transformer", "1.0"); }
        public void initialize(PluginContext context) { this.context = context; }
        public void start() { context.getInstrumentationManager().registerTransformer(this, false); }
        public void stop() { }
        public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) throws IllegalClassFormatException { return null; }
    }

    public static class ReloadableTestPlugin implements AgentPlugin, ConfigurablePlugin {
        public PluginDescriptor descriptor() { return new PluginDescriptor("config-plugin", "Config", "1.0"); }
        public void initialize(PluginContext context) { }
        public void start() { }
        public void stop() { }
        public void onConfigurationChanged(PluginConfiguration configuration) throws PluginException {
            if (!"accepted".equals(configuration.get("mode"))) throw new PluginException("invalid mode");
        }
    }

    public interface SharedService { String value(); }
    public static class ProviderPlugin implements AgentPlugin {
        private PluginContext context;
        public PluginDescriptor descriptor() { return new PluginDescriptor("provider", "Provider", "1.0.0"); }
        public void initialize(PluginContext context) {
            this.context = context;
            context.getExtensionRegistry().register("provider", SharedService.class, new SharedService() { public String value() { return "ready"; } });
        }
        public void start() { }
        public void stop() { }
    }
    public static class ConsumerPlugin implements AgentPlugin {
        private PluginContext context;
        public PluginDescriptor descriptor() { return new PluginDescriptor("consumer", "Consumer", "1.0.0"); }
        public void initialize(PluginContext context) { this.context = context; }
        public void start() { dependencyObserved = "ready".equals(context.getExtensionRegistry().get(SharedService.class).value()); }
        public void stop() { }
    }
    public static class ResourcePlugin implements AgentPlugin, AutoCloseable {
        private PluginContext context;
        public PluginDescriptor descriptor() { return new PluginDescriptor("resource-plugin", "Resource", "1.0.0"); }
        public void initialize(PluginContext context) { this.context = context; }
        public void start() { context.getResourceManager().register(this); concurrentStarts.incrementAndGet(); }
        public void stop() { }
        public void close() { resourceCloses.incrementAndGet(); }
    }
}
