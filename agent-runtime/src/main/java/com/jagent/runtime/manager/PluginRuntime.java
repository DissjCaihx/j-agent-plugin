package com.jagent.runtime.manager;

import com.jagent.api.*;
import com.jagent.api.version.VersionRange;
import com.jagent.api.configuration.ConfigurablePlugin;
import com.jagent.api.event.PluginEventBus;
import com.jagent.api.extension.ExtensionRegistry;
import com.jagent.runtime.classloader.PluginClassLoader;
import com.jagent.runtime.configuration.PropertiesPluginConfiguration;
import com.jagent.runtime.configuration.ReloadablePluginConfiguration;
import com.jagent.runtime.configuration.AgentConfiguration;
import com.jagent.runtime.configuration.AgentConfigurationReader;
import com.jagent.runtime.discovery.PluginMetadata;
import com.jagent.runtime.discovery.PluginMetadataReader;
import com.jagent.runtime.discovery.PluginCompatibilityValidator;
import com.jagent.runtime.discovery.PluginDependency;
import com.jagent.runtime.event.PluginEventBusImpl;
import com.jagent.runtime.extension.ExtensionRegistryImpl;
import com.jagent.runtime.lifecycle.PluginState;
import com.jagent.runtime.logging.RuntimeLog;
import com.jagent.runtime.installation.PluginInstallResult;
import com.jagent.runtime.installation.PluginPackageInstaller;

import java.io.*;
import java.lang.instrument.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;

public final class PluginRuntime {
    private interface PluginCall {
        void run() throws Exception;
    }

    private final Instrumentation instrumentation;
    private final PluginEventBus eventBus = new PluginEventBusImpl();
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistryImpl();
    private final Map<String, ManagedPlugin> plugins = new LinkedHashMap<String, ManagedPlugin>();
    private Path pluginDirectory;

    public PluginRuntime(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stopAll();
            }
        }, "j-agent-plugin-shutdown"));
    }

    public void discoverAndStart(Path directory) {
        discoverAndStart(directory, AgentConfiguration.defaults());
    }

    public void discoverAndStart(Path directory, Path agentConfigFile) {
        AgentConfiguration configuration;
        try {
            configuration = new AgentConfigurationReader().read(agentConfigFile);
        } catch (Exception e) {
            RuntimeLog.error("configuration", "Invalid agent configuration; plugins will be loaded but not auto-started", e);
            configuration = AgentConfiguration.enabledPlugins(Collections.<String>emptySet());
        }
        String configuredLevel = System.getProperty("jagent.log.level", configuration.getLogLevel());
        RuntimeLog.configure(configuredLevel == null ? "INFO" : configuredLevel);
        discoverAndStart(directory, configuration);
    }

    private void discoverAndStart(Path directory, AgentConfiguration configuration) {
        pluginDirectory = directory.toAbsolutePath().normalize();
        try {
            PluginPackageInstaller.activatePending(pluginDirectory);
        } catch (IOException e) {
            RuntimeLog.error("installation", "Cannot activate pending plugin package", e);
        }
        if (!Files.isDirectory(directory)) return;
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream)
                if (Files.isDirectory(path) && !path.getFileName().toString().startsWith(".")) candidates.add(path);
        } catch (IOException e) {
            RuntimeLog.error("discovery", "Cannot scan plugin directory " + directory, e);
        }
        Collections.sort(candidates);
        RuntimeLog.info("discovery", "Scanning " + candidates.size() + " plugin directories under " + directory);
        for (Path path : candidates) discover(path);
        List<ManagedPlugin> order = new ArrayList<ManagedPlugin>();
        Map<String, Integer> marks = new HashMap<String, Integer>();
        for (ManagedPlugin plugin : new ArrayList<ManagedPlugin>(plugins.values())) {
            try {
                visit(plugin, marks, order);
            } catch (IllegalStateException e) {
                plugin.fail(e);
            }
        }
        Set<String> startIds = new LinkedHashSet<String>();
        if (configuration.hasExplicitPluginSelection()) {
            startIds.addAll(configuration.getEnabledPlugins());
            for (String enabledId : configuration.getEnabledPlugins())
                if (!plugins.containsKey(enabledId))
                    RuntimeLog.warn("configuration", "Configured plugin is not installed: " + enabledId);
        } else {
            for (ManagedPlugin plugin : plugins.values()) if (plugin.metadata.isAutoStart()) startIds.add(plugin.id);
        }
        addDependencyClosure(startIds);
        for (ManagedPlugin plugin : order) {
            if (plugin.state == PluginState.FAILED) continue;
            try {
                plugin.load();
            } catch (Exception e) {
                plugin.fail(e);
                continue;
            }
            if (!startIds.contains(plugin.id)) {
                RuntimeLog.debug("plugin:" + plugin.id, "Loaded but not selected for startup");
                continue;
            }
            for (PluginDependency dependency : plugin.metadata.getDependencies()) {
                ManagedPlugin required = plugins.get(dependency.getPluginId());
                if (required == null || required.state != PluginState.ACTIVE) {
                    plugin.fail(new IllegalStateException("Required plugin is not active: " + dependency.getPluginId()));
                    break;
                }
            }
            if (plugin.state == PluginState.FAILED) continue;
            try {
                plugin.start();
            } catch (Exception e) {
                plugin.fail(e);
            }
        }
    }

    private void addDependencyClosure(Set<String> startIds) {
        Deque<String> pending = new ArrayDeque<String>(startIds);
        while (!pending.isEmpty()) {
            ManagedPlugin plugin = plugins.get(pending.removeFirst());
            if (plugin == null) continue;
            for (PluginDependency dependency : plugin.metadata.getDependencies()) {
                if (plugins.containsKey(dependency.getPluginId()) && startIds.add(dependency.getPluginId()))
                    pending.addLast(dependency.getPluginId());
            }
        }
    }

    private void visit(ManagedPlugin plugin, Map<String, Integer> marks, List<ManagedPlugin> order) {
        if (plugin.state == PluginState.FAILED) return;
        Integer mark = marks.get(plugin.id);
        if (mark != null && mark == 2) return;
        if (mark != null && mark == 1) throw new IllegalStateException("Plugin dependency cycle includes " + plugin.id);
        marks.put(plugin.id, 1);
        for (PluginDependency dependency : plugin.metadata.getDependencies()) {
            ManagedPlugin required = plugins.get(dependency.getPluginId());
            if (required == null)
                throw new IllegalStateException("Missing plugin dependency: " + dependency.getPluginId());
            if (!VersionRange.includes(required.version, dependency.getVersionRange()))
                throw new IllegalStateException("Plugin " + plugin.id + " requires " + required.id + " " + dependency.getVersionRange() + " but found " + required.version);
            visit(required, marks, order);
        }
        marks.put(plugin.id, 2);
        if (!order.contains(plugin)) order.add(plugin);
    }

    public synchronized Collection<PluginInfo> plugins() {
        List<PluginInfo> result = new ArrayList<PluginInfo>();
        for (ManagedPlugin plugin : plugins.values()) result.add(plugin.info());
        return result;
    }

    public synchronized PluginInfo getPlugin(String id) {
        return requirePlugin(id).info();
    }

    public synchronized PluginInfo startPlugin(String id) {
        ManagedPlugin plugin = requirePlugin(id);
        for (PluginDependency dependency : plugin.metadata.getDependencies()) {
            ManagedPlugin required = plugins.get(dependency.getPluginId());
            if (required == null || required.state != PluginState.ACTIVE)
                throw new IllegalStateException("Required plugin is not active: " + dependency.getPluginId());
        }
        plugin.start();
        return plugin.info();
    }

    public synchronized PluginInfo stopPlugin(String id) {
        ManagedPlugin plugin = requirePlugin(id);
        for (ManagedPlugin candidate : plugins.values()) {
            if (candidate.state == PluginState.ACTIVE)
                for (PluginDependency dependency : candidate.metadata.getDependencies())
                    if (dependency.getPluginId().equals(id))
                        throw new IllegalStateException("Cannot stop plugin while dependent plugin is active: " + candidate.id);
        }
        plugin.stop();
        return plugin.info();
    }

    public synchronized PluginInfo reloadPluginConfiguration(String id) {
        ManagedPlugin plugin = requirePlugin(id);
        plugin.reloadConfiguration();
        return plugin.info();
    }

    public synchronized PluginInstallResult installPluginPackage(InputStream archive) throws IOException {
        return new PluginPackageInstaller(requirePluginDirectory()).install(archive, false, null);
    }

    public synchronized PluginInstallResult updatePluginPackage(String id, InputStream archive) throws IOException {
        return new PluginPackageInstaller(requirePluginDirectory()).install(archive, true, id);
    }

    private Path requirePluginDirectory() {
        if (pluginDirectory == null) throw new IllegalStateException("Plugin directory is not initialized");
        return pluginDirectory;
    }

    private ManagedPlugin requirePlugin(String id) {
        ManagedPlugin plugin = plugins.get(id);
        if (plugin == null) throw new IllegalArgumentException("Unknown plugin: " + id);
        return plugin;
    }

    private synchronized void stopAll() {
        List<ManagedPlugin> order = new ArrayList<ManagedPlugin>();
        Map<String, Integer> marks = new HashMap<String, Integer>();
        for (ManagedPlugin plugin : plugins.values()) {
            try {
                visit(plugin, marks, order);
            } catch (IllegalStateException ignored) {
            }
        }
        Collections.reverse(order);
        for (ManagedPlugin plugin : order) plugin.stop();
    }

    private void discover(Path directory) {
        try {
            PluginMetadata metadata = new PluginMetadataReader().read(directory.resolve("plugin.yaml"));
            String id = metadata.getId();
            ManagedPlugin managed = new ManagedPlugin(metadata, directory);
            managed.state = PluginState.DISCOVERED;
            synchronized (this) {
                if (plugins.containsKey(id)) throw new IllegalStateException("Duplicate plugin: " + id);
                plugins.put(id, managed);
            }
        } catch (Exception e) {
            RuntimeLog.error("discovery", "Plugin discovery failed for " + directory, e);
        }
    }

    private final class ManagedPlugin {
        final PluginMetadata metadata;
        final String id, name, version, entryClass;
        final Path directory;
        PluginState state;
        AgentPlugin plugin;
        PluginClassLoader loader;
        String failure;
        Context context;
        ReloadablePluginConfiguration configuration;

        ManagedPlugin(PluginMetadata metadata, Path directory) {
            this.metadata = metadata;
            this.id = metadata.getId();
            this.name = metadata.getName();
            this.version = metadata.getVersion();
            this.directory = directory;
            this.entryClass = metadata.getEntryClass();
        }

        void load() throws Exception {
            PluginCompatibilityValidator.validateRuntime(metadata, directory);
            state = PluginState.VALIDATED;
            List<URL> urls = new ArrayList<URL>();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : jars) urls.add(jar.toUri().toURL());
            }
            Path lib = directory.resolve("lib");
            if (Files.isDirectory(lib)) try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "*.jar")) {
                for (Path jar : jars) urls.add(jar.toUri().toURL());
            }
            loader = new PluginClassLoader(urls.toArray(new URL[urls.size()]), PluginRuntime.class.getClassLoader());
            plugin = (AgentPlugin) loader.loadClass(entryClass).newInstance();
            RuntimeLog.debug("plugin:" + id, "Loaded entry class " + entryClass);
            state = PluginState.LOADED;
            configuration = new ReloadablePluginConfiguration(new PropertiesPluginConfiguration(directory.resolve("conf").resolve("config.properties")));
            context = new Context(configuration);
            invoke(new PluginCall() {
                public void run() throws Exception {
                    plugin.initialize(context);
                }
            });
            state = PluginState.INITIALIZED;
        }

        synchronized PluginInfo info() {
            return new PluginInfo(id, name, version, state, failure);
        }

        synchronized void fail(Exception error) {
            failure = error.toString();
            state = PluginState.FAILED;
            RuntimeLog.error("plugin:" + id, "Plugin entered FAILED state", error);
            if (context != null) context.cleanup();
            if (loader != null) try {
                loader.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
        }

        synchronized void reloadConfiguration() {
            if (!(plugin instanceof ConfigurablePlugin))
                throw new IllegalStateException("Plugin does not support configuration reload: " + id);
            if (state != PluginState.ACTIVE)
                throw new IllegalStateException("Plugin must be active to reload configuration: " + id);
            try {
                final PluginConfiguration candidate = new PropertiesPluginConfiguration(directory.resolve("conf").resolve("config.properties"));
                invoke(new PluginCall() {
                    public void run() throws Exception {
                        ((ConfigurablePlugin) plugin).onConfigurationChanged(candidate);
                    }
                });
                configuration.replace(candidate);
            } catch (Exception e) {
                throw new IllegalStateException("Configuration reload failed for " + id + ": " + e.getMessage(), e);
            }
        }

        synchronized void start() {
            if (state == PluginState.ACTIVE || state == PluginState.STARTING) return;
            if (state == PluginState.STOPPING || state == PluginState.STOPPED)
                throw new IllegalStateException("Plugin cannot restart without JVM restart: " + id);
            if (state == PluginState.FAILED)
                throw new IllegalStateException("Plugin is failed: " + id + ": " + failure);
            long startedAt = System.nanoTime();
            try {
                state = PluginState.STARTING;
                invoke(new PluginCall() {
                    public void run() throws Exception {
                        plugin.start();
                    }
                });
                state = PluginState.ACTIVE;
                RuntimeLog.info("plugin:" + id, "Started in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) + " ms");
            } catch (Exception e) {
                failure = e.toString();
                state = PluginState.FAILED;
                RuntimeLog.error("plugin:" + id, "Plugin start failed", e);
            }
        }

        synchronized void stop() {
            if (state == PluginState.STOPPED || state == PluginState.DISCOVERED) return;
            if (state == PluginState.STOPPING) return;
            PluginState previous = state;
            state = PluginState.STOPPING;
            if (plugin != null && (previous == PluginState.ACTIVE || previous == PluginState.FAILED || previous == PluginState.INITIALIZED))
                stopWithTimeout();
            if (context != null) context.cleanup();
            state = PluginState.STOPPED;
            try {
                loader.close();
            } catch (IOException e) {
                RuntimeLog.error("plugin:" + id, "Plugin class loader close failed", e);
            }
        }

        private void stopWithTimeout() {
            FutureTask<Void> task = new FutureTask<Void>(new Callable<Void>() {
                public Void call() throws Exception {
                    invoke(new PluginCall() {
                        public void run() throws Exception {
                            plugin.stop();
                        }
                    });
                    return null;
                }
            });
            Thread thread = new Thread(task, "j-agent-stop-" + id);
            thread.setDaemon(true);
            thread.setContextClassLoader(loader);
            thread.start();
            long timeout = Long.getLong("jagent.plugin.stop.timeoutMillis", 10000L);
            try {
                task.get(Math.max(1L, timeout), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                task.cancel(true);
                failure = "Plugin stop timed out after " + timeout + " ms";
                RuntimeLog.warn("plugin:" + id, failure);
            } catch (Exception e) {
                failure = e.toString();
                RuntimeLog.error("plugin:" + id, "Plugin stop failed", e);
            }
        }

        void invoke(PluginCall call) throws Exception {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                call.run();
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        final class Context implements PluginContext {
            private final List<ClassFileTransformer> transformers = new ArrayList<ClassFileTransformer>();
            private final List<AutoCloseable> resources = new ArrayList<AutoCloseable>();
            private final PluginConfiguration configuration;

            Context(PluginConfiguration configuration) {
                this.configuration = configuration;
            }

            public String getPluginId() {
                return id;
            }

            public PluginConfiguration getConfiguration() {
                return configuration;
            }

            public PluginLogger getLogger() {
                return new PluginLogger() {
                    public void info(String m) {
                        RuntimeLog.info("plugin:" + id, m);
                    }

                    public void warn(String m) {
                        RuntimeLog.warn("plugin:" + id, m);
                    }

                    public void error(String m, Throwable e) {
                        RuntimeLog.error("plugin:" + id, m, e);
                    }
                };
            }

            public InstrumentationManager getInstrumentationManager() {
                return new InstrumentationManager() {
                    public void registerTransformer(ClassFileTransformer t, boolean r) {
                        instrumentation.addTransformer(t, r);
                        transformers.add(t);
                    }

                    public void unregisterTransformer(ClassFileTransformer t) {
                        instrumentation.removeTransformer(t);
                        transformers.remove(t);
                    }
                };
            }

            public PluginResourceManager getResourceManager() {
                return new PluginResourceManager() {
                    public void register(AutoCloseable r) {
                        resources.add(r);
                    }
                };
            }

            public PluginEventBus getEventBus() {
                return eventBus;
            }

            public ExtensionRegistry getExtensionRegistry() {
                return extensionRegistry;
            }

            public String getFrameworkVersionRange(String framework) {
                return metadata.getCompatibility().getFrameworkRanges().get(framework);
            }

            void cleanup() {
                for (ClassFileTransformer transformer : transformers) instrumentation.removeTransformer(transformer);
                for (AutoCloseable resource : resources)
                    try {
                        resource.close();
                    } catch (Exception e) {
                        RuntimeLog.error("plugin:" + id, "Managed resource close failed", e);
                    }
                eventBus.unsubscribeAll(id);
                extensionRegistry.unregisterAll(id);
                transformers.clear();
                resources.clear();
            }
        }
    }
}
