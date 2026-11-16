package com.jagent.plugins.springbootnacos.configcenter;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.jagent.api.PluginLogger;
import com.jagent.api.event.PluginEventBus;
import com.jagent.plugins.springbootnacos.config.NacosSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public final class NacosConfiguration implements AutoCloseable {
    private final NacosSettings settings;
    private final PluginLogger logger;
    private final PluginEventBus eventBus;
    private final Map<String, Listener> listeners = new HashMap<String, Listener>();
    private final Map<String, AtomicReference<String>> snapshots = new HashMap<String, AtomicReference<String>>();
    private final List<PluginEventBus.Subscription> subscriptions = new ArrayList<PluginEventBus.Subscription>();
    private ConfigService config;
    private volatile boolean closed;

    public NacosConfiguration(NacosSettings settings, PluginLogger logger, PluginEventBus eventBus) {
        this.settings = settings;
        this.logger = logger;
        this.eventBus = eventBus;
    }

    @SuppressWarnings("unchecked")
    public void start() throws Exception {
        logger.info("Initializing Nacos Config client at " + settings.getServerAddress());
        try {
            config = NacosFactory.createConfigService(settings.getClientProperties());
        } catch (Exception e) {
            logger.error("Nacos Config client initialization failed for " + settings.getServerAddress(), e);
            throw e;
        }
        for (final String dataId : settings.getConfigDataIds()) {
            listen(dataId, settings.getConfigGroup());
            logger.info("Initial Nacos config loaded and listener registered: dataId=" + dataId
                    + ", group=" + settings.getConfigGroup() + ", content-present="
                    + (snapshots.get(key(dataId, settings.getConfigGroup())).get() != null) + " (content omitted)");
        }
        subscriptions.add(eventBus.subscribe("springboot-nacos", Map.class, event -> handleRequest(event)));
    }

    @SuppressWarnings("unchecked")
    private void handleRequest(Map request) {
        if (closed) return;
        try {
            String action = String.valueOf(request.get("action"));
            if (!"springboot-nacos.config.get".equals(action)
                    && !"springboot-nacos.config.subscribe".equals(action)
                    && !"springboot-nacos.config.unsubscribe".equals(action)) return;
            String dataId = required(request, "dataId");
            String group = request.get("group") == null ? settings.getConfigGroup() : request.get("group").toString();
            if ("springboot-nacos.config.get".equals(action)) {
                request.put("content", config.getConfig(dataId, group, settings.getTimeoutMs()));
            } else if ("springboot-nacos.config.subscribe".equals(action)) {
                listen(dataId, group);
            } else if ("springboot-nacos.config.unsubscribe".equals(action)) {
                unlisten(dataId, group);
            } else return;
            request.put("success", Boolean.TRUE);
        } catch (Exception e) {
            String error = safe(e);
            logger.error("Nacos config request failed: action=" + String.valueOf(request.get("action"))
                    + ", dataId=" + String.valueOf(request.get("dataId")) + ", reason=" + error, e);
            request.put("success", Boolean.FALSE);
            request.put("error", error);
        }
    }

    private synchronized void listen(final String dataId, final String group) throws Exception {
        final String key = key(dataId, group);
        if (listeners.containsKey(key)) return;
        String initial;
        try {
            initial = config.getConfig(dataId, group, settings.getTimeoutMs());
        } catch (Exception e) {
            logger.error("Could not fetch Nacos config: dataId=" + dataId + ", group=" + group
                    + ", namespace=" + settings.getNamespace(), e);
            throw e;
        }
        snapshots.put(key, new AtomicReference<String>(initial));
        Listener listener = new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String content) {
                if (closed) return;
                AtomicReference<String> snapshot = snapshots.get(key);
                if (snapshot == null) return;
                String previous = snapshot.getAndSet(content);
                if (content == null ? previous == null : content.equals(previous)) return;
                Map<String, Object> event = new HashMap<String, Object>();
                event.put("type", "springboot-nacos.config.changed");
                event.put("dataId", dataId);
                event.put("group", group);
                event.put("content", content);
                eventBus.publish(event);
                logger.info("Nacos config changed: " + dataId + " (content omitted)");
            }
        };
        try {
            config.addListener(dataId, group, listener);
        } catch (Exception e) {
            logger.error("Could not register Nacos config listener: dataId=" + dataId + ", group=" + group, e);
            throw e;
        }
        logger.info("Registered Nacos config listener: dataId=" + dataId + ", group=" + group);
        listeners.put(key, listener);
    }

    private synchronized void unlisten(String dataId, String group) {
        String key = key(dataId, group);
        Listener listener = listeners.remove(key);
        snapshots.remove(key);
        if (listener != null) config.removeListener(dataId, group, listener);
    }

    @Override
    public void close() throws Exception {
        closed = true;
        for (PluginEventBus.Subscription subscription : subscriptions) subscription.close();
        if (config == null) return;
        for (String key : new ArrayList<String>(listeners.keySet())) {
            String[] parts = key.split("@@", 2);
            try {
                unlisten(parts[1], parts[0]);
            } catch (Exception e) {
                logger.error("Failed to remove config listener for " + parts[1], e);
            }
        }
        listeners.clear();
        snapshots.clear();
        config.shutDown();
    }

    private static String required(Map request, String key) {
        Object value = request.get(key);
        if (value == null || value.toString().trim().isEmpty())
            throw new IllegalArgumentException(key + " is required");
        return value.toString().trim();
    }

    private static String key(String dataId, String group) {
        return group + "@@" + dataId;
    }

    private static String safe(Exception e) {
        return e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "operation failed" : e.getMessage().replaceAll("(?i)(password|token|secret)=([^,; ]+)", "$1=****"));
    }
}
