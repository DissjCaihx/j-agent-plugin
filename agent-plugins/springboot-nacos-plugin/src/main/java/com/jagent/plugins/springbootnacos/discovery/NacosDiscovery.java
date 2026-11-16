package com.jagent.plugins.springbootnacos.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.jagent.api.InstrumentationManager;
import com.jagent.api.PluginLogger;
import com.jagent.api.event.PluginEventBus;
import com.jagent.api.lifecycle.ApplicationContextLifecycle;
import com.jagent.api.version.VersionRange;
import com.jagent.plugins.springbootnacos.config.NacosSettings;
import com.jagent.plugins.springbootnacos.lifecycle.SpringBootContextInfo;
import com.jagent.plugins.springbootnacos.lifecycle.SpringBootLifecycleTransformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NacosDiscovery implements AutoCloseable {
    private final NacosSettings settings;
    private final PluginLogger logger;
    private final PluginEventBus eventBus;
    private final InstrumentationManager instrumentation;
    private final String springBootVersionRange;
    private final List<PluginEventBus.Subscription> subscriptions = new ArrayList<PluginEventBus.Subscription>();
    private final Map<String, EventListener> listeners = new ConcurrentHashMap<String, EventListener>();
    private final Map<Object, RegisteredService> registeredContexts = new IdentityHashMap<Object, RegisteredService>();
    private NamingService naming;
    private SpringBootLifecycleTransformer transformer;
    private ApplicationContextLifecycle.Registration contextRegistration;
    private volatile boolean closed;

    public NacosDiscovery(NacosSettings settings, PluginLogger logger, PluginEventBus eventBus,
                          InstrumentationManager instrumentation, String springBootVersionRange) {
        this.settings = settings;
        this.logger = logger;
        this.eventBus = eventBus;
        this.instrumentation = instrumentation;
        this.springBootVersionRange = springBootVersionRange;
    }

    public void start() throws Exception {
        logger.info("Initializing Nacos Naming client at " + settings.getServerAddress());
        try {
            naming = NacosFactory.createNamingService(settings.getClientProperties());
        } catch (Exception e) {
            logger.error("Nacos Naming client initialization failed for " + settings.getServerAddress(), e);
            throw e;
        }
        subscriptions.add(eventBus.subscribe("springboot-nacos", Map.class, event -> handleRequest(event)));
        if (settings.isRegisterEnabled()) {
            contextRegistration = ApplicationContextLifecycle.addListener(new ApplicationContextLifecycle.Listener() {
                @Override
                public void onStarted(Object context) {
                    registerContext(context);
                }

                @Override
                public void onStopped(Object context) {
                    deregisterContext(context);
                }

                @Override
                public void onFailure(Object context, Throwable error) {
                    logger.error("Spring Boot lifecycle callback failed for " + context.getClass().getName(), error);
                }
            });
            transformer = new SpringBootLifecycleTransformer(logger);
            instrumentation.registerTransformer(transformer, false);
            logger.info("Installed Spring Boot registration hook for servlet/reactive WebServer lifecycle; waiting for actual port and application name");
        }
        for (String service : settings.getSubscribedServices()) subscribe(service, settings.getGroupName());
    }

    private synchronized void registerContext(Object context) {
        if (closed || registeredContexts.containsKey(context)) return;
        try {
            if (springBootVersionRange != null) {
                String actualVersion = SpringBootContextInfo.springBootVersion(context);
                if (actualVersion == null || !VersionRange.includes(actualVersion, springBootVersionRange)) {
                    logger.error("Spring Boot version is outside the declared supported range: expected "
                            + springBootVersionRange + ", found " + (actualVersion == null ? "unknown" : actualVersion), null);
                    return;
                }
            }
            SpringBootContextInfo info = SpringBootContextInfo.read(context, settings);
            logger.info("Spring Boot context ready: context=" + context.getClass().getName() + ", service="
                    + info.getServiceName() + ", group=" + info.getGroupName() + ", ip=" + info.getIp()
                    + ", port=" + info.getPort() + ", cluster=" + info.getClusterName());
            Instance instance = new Instance();
            instance.setServiceName(info.getServiceName());
            instance.setIp(info.getIp());
            instance.setPort(info.getPort());
            instance.setClusterName(info.getClusterName());
            instance.setWeight(info.getWeight());
            instance.setEphemeral(info.isEphemeral());
            instance.setHealthy(true);
            instance.setEnabled(true);
            instance.setMetadata(info.getMetadata());
            naming.registerInstance(info.getServiceName(), info.getGroupName(), instance);
            registeredContexts.put(context, new RegisteredService(info));
            logger.info("Registered Spring Boot service " + info.getServiceName() + " at " + info.getIp() + ":" + info.getPort());
        } catch (Exception e) {
            logger.error("Could not register service from Spring Boot context " + context.getClass().getName()
                    + ": " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()), e);
        }
    }

    private synchronized void deregisterContext(Object context) {
        RegisteredService registered = registeredContexts.remove(context);
        if (registered == null || naming == null) return;
        SpringBootContextInfo info = registered.info;
        try {
            naming.deregisterInstance(info.getServiceName(), info.getGroupName(), info.getIp(), info.getPort(), info.getClusterName());
            logger.info("Deregistered Spring Boot service " + info.getServiceName() + " at " + info.getIp() + ":" + info.getPort());
        } catch (Exception e) {
            logger.error("Could not deregister Spring Boot service " + info.getServiceName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRequest(Map request) {
        if (closed) return;
        try {
            String action = String.valueOf(request.get("action"));
            if (!"springboot-nacos.discovery.query".equals(action)
                    && !"springboot-nacos.discovery.subscribe".equals(action)
                    && !"springboot-nacos.discovery.unsubscribe".equals(action)) return;
            String service = required(request, "serviceName");
            String group = optional(request, "groupName", settings.getGroupName());
            if ("springboot-nacos.discovery.query".equals(action)) {
                request.put("instances", instances(naming.getAllInstances(service, group)));
            } else if ("springboot-nacos.discovery.subscribe".equals(action)) {
                subscribe(service, group);
            } else if ("springboot-nacos.discovery.unsubscribe".equals(action)) {
                unsubscribe(service, group);
            } else return;
            request.put("success", Boolean.TRUE);
        } catch (Exception e) {
            String error = safe(e);
            logger.error("Nacos discovery request failed: action=" + actionName(request) + ", reason=" + error, e);
            request.put("success", Boolean.FALSE);
            request.put("error", error);
        }
    }

    private void subscribe(final String serviceName, final String groupName) throws Exception {
        final String key = groupName + "@@" + serviceName;
        if (listeners.containsKey(key)) return;
        EventListener listener = event -> {
            if (closed) return;
            Map<String, Object> update = new HashMap<String, Object>();
            update.put("type", "springboot-nacos.discovery.changed");
            update.put("serviceName", serviceName);
            update.put("groupName", groupName);
            if (event instanceof NamingEvent) update.put("instances", instances(((NamingEvent) event).getInstances()));
            else try {
                update.put("instances", instances(naming.getAllInstances(serviceName, groupName)));
            } catch (Exception e) {
                update.put("error", safe(e));
            }
            eventBus.publish(update);
        };
        naming.subscribe(serviceName, groupName, listener);
        listeners.put(key, listener);
        logger.info("Subscribed to service changes: " + serviceName + " (" + groupName + ")");
    }

    private void unsubscribe(String serviceName, String groupName) throws Exception {
        String key = groupName + "@@" + serviceName;
        EventListener listener = listeners.remove(key);
        if (listener != null) naming.unsubscribe(serviceName, groupName, listener);
    }

    private static List<Map<String, Object>> instances(List<Instance> values) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Instance instance : values) {
            Map<String, Object> value = new HashMap<String, Object>();
            value.put("ip", instance.getIp());
            value.put("port", instance.getPort());
            value.put("serviceName", instance.getServiceName());
            value.put("clusterName", instance.getClusterName());
            value.put("weight", instance.getWeight());
            value.put("healthy", instance.isHealthy());
            value.put("enabled", instance.isEnabled());
            value.put("ephemeral", instance.isEphemeral());
            value.put("metadata", instance.getMetadata());
            result.add(value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void close() throws Exception {
        closed = true;
        if (contextRegistration != null) contextRegistration.close();
        if (transformer != null) instrumentation.unregisterTransformer(transformer);
        for (Object context : new ArrayList<Object>(registeredContexts.keySet())) deregisterContext(context);
        if (settings.isRegisterEnabled() && registeredContexts.isEmpty()) {
            logger.warn("No Spring Boot service instance was registered during this plugin run; check the preceding context, hook and Nacos error details");
        }
        for (PluginEventBus.Subscription subscription : subscriptions) subscription.close();
        if (naming == null) return;
        for (Map.Entry<String, EventListener> entry : listeners.entrySet()) {
            String[] parts = entry.getKey().split("@@", 2);
            try {
                unsubscribe(parts[1], parts[0]);
            } catch (Exception e) {
                logger.error("Failed to unsubscribe " + parts[1], e);
            }
        }
        listeners.clear();
        naming.shutDown();
    }

    private static final class RegisteredService {
        final SpringBootContextInfo info;

        RegisteredService(SpringBootContextInfo info) {
            this.info = info;
        }
    }

    private static String actionName(Map request) {
        Object action = request.get("action");
        return action == null ? "<unknown>" : action.toString();
    }

    private static String required(Map request, String key) {
        Object value = request.get(key);
        if (value == null || value.toString().trim().isEmpty())
            throw new IllegalArgumentException(key + " is required");
        return value.toString().trim();
    }

    private static String optional(Map request, String key, String fallback) {
        Object value = request.get(key);
        return value == null ? fallback : value.toString();
    }

    private static String safe(Exception e) {
        return e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "operation failed" : e.getMessage().replaceAll("(?i)(password|token|secret)=([^,; ]+)", "$1=****"));
    }
}
