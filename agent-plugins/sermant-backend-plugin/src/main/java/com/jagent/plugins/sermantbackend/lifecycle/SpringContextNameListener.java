package com.jagent.plugins.sermantbackend.lifecycle;

import com.jagent.api.PluginLogger;
import com.jagent.api.version.VersionRange;
import com.jagent.api.lifecycle.ApplicationContextLifecycle;
import com.jagent.plugins.sermantbackend.model.AgentIdentity;

import java.lang.reflect.Method;

public final class SpringContextNameListener implements ApplicationContextLifecycle.Listener {
    private final AgentIdentity identity;
    private final PluginLogger logger;
    private final String supportedRange;
    public SpringContextNameListener(AgentIdentity identity, PluginLogger logger, String supportedRange) {
        this.identity = identity; this.logger = logger; this.supportedRange = supportedRange;
    }
    @Override public void onStarted(Object context) {
        try {
            if (supportedRange != null) {
                ClassLoader loader = context.getClass().getClassLoader();
                Class<?> versionClass = Class.forName("org.springframework.boot.SpringBootVersion", false, loader);
                Object actual = versionClass.getMethod("getVersion").invoke(null);
                String version = actual == null ? null : String.valueOf(actual);
                if (version == null || !VersionRange.includes(version, supportedRange)) {
                    logger.warn("Sermant Backend Spring Boot integration supports " + supportedRange
                            + " but found " + (version == null ? "unknown" : version));
                    return;
                }
            }
            Object environment = context.getClass().getMethod("getEnvironment").invoke(context);
            Method getProperty = environment.getClass().getMethod("getProperty", String.class);
            Object value = getProperty.invoke(environment, "spring.application.name");
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                identity.updateApplicationName(String.valueOf(value).trim());
                logger.info("Using Spring application name for Sermant Backend heartbeat: " + identity.getServiceName());
            }
        } catch (Throwable error) {
            logger.warn("Could not read spring.application.name for Sermant Backend heartbeat: "
                    + error.getClass().getSimpleName());
        }
    }
    @Override public void onStopped(Object context) { }
}
