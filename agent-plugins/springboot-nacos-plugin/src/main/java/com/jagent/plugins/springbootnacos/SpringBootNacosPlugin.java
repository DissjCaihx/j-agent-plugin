package com.jagent.plugins.springbootnacos;

import com.jagent.api.AgentPlugin;
import com.jagent.api.InstrumentationManager;
import com.jagent.api.PluginContext;
import com.jagent.api.PluginDescriptor;
import com.jagent.api.PluginException;
import com.jagent.api.PluginLogger;
import com.jagent.api.PluginConfiguration;
import com.jagent.api.version.VersionRange;
import com.jagent.api.event.PluginEventBus;
import com.jagent.plugins.springbootnacos.config.NacosSettings;
import com.jagent.plugins.springbootnacos.discovery.NacosDiscovery;
import com.jagent.plugins.springbootnacos.configcenter.NacosConfiguration;

public final class SpringBootNacosPlugin implements AgentPlugin {
    private PluginLogger logger;
    private PluginEventBus eventBus;
    private InstrumentationManager instrumentation;
    private NacosSettings settings;
    private NacosDiscovery discovery;
    private NacosConfiguration configuration;
    private String springBootVersionRange;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("springboot-nacos", "Spring Boot Nacos", "0.1.0");
    }

    @Override
    public void initialize(PluginContext context) throws PluginException {
        logger = context.getLogger();
        eventBus = context.getEventBus();
        instrumentation = context.getInstrumentationManager();
        springBootVersionRange = context.getFrameworkVersionRange("spring-boot");
        try {
            settings = NacosSettings.from(context.getConfiguration());
        } catch (RuntimeException e) {
            throw new PluginException("Invalid springboot-nacos configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public void start() throws PluginException {
        if (!settings.isEnabled()) {
            logger.info("Nacos integration disabled by configuration");
            return;
        }
        logger.info("Nacos settings: server-addr=" + settings.getServerAddress() + ", namespace="
                + (settings.getNamespace().isEmpty() ? "<public>" : settings.getNamespace())
                + ", username-configured=" + settings.hasUsername() + ", password-configured=" + settings.hasPassword()
                + ", config-enabled=" + settings.isConfigEnabled() + ", discovery-enabled=" + settings.isDiscoveryEnabled()
                + ", auto-register=" + settings.isRegisterEnabled());
        try {
            if (settings.isConfigEnabled()) {
                configuration = new NacosConfiguration(settings, logger, eventBus);
                configuration.start();
            }
            if (settings.isDiscoveryEnabled()) {
                discovery = new NacosDiscovery(settings, logger, eventBus, instrumentation, springBootVersionRange);
                discovery.start();
            }
            logger.info("Nacos integration started; namespace=" + settings.getNamespace()
                    + ", config=" + settings.isConfigEnabled() + ", discovery=" + settings.isDiscoveryEnabled());
        } catch (Exception e) {
            stopResources();
            logger.error("Nacos integration startup failed: " + safeMessage(e), e);
            throw new PluginException("Cannot start Nacos integration: " + safeMessage(e));
        }
    }

    @Override
    public void stop() {
        stopResources();
        logger.info("Nacos integration stopped");
    }

    private void stopResources() {
        if (discovery != null) {
            try {
                discovery.close();
            } catch (Exception e) {
                logger.error("Nacos discovery cleanup failed", e);
            }
            discovery = null;
        }
        if (configuration != null) {
            try {
                configuration.close();
            } catch (Exception e) {
                logger.error("Nacos config cleanup failed", e);
            }
            configuration = null;
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.replaceAll("(?i)(password|token|secret)=([^,; ]+)", "$1=****");
    }

}
