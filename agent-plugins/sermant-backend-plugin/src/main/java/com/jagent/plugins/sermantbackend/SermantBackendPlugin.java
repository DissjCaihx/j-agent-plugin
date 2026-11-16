package com.jagent.plugins.sermantbackend;

import com.jagent.api.AgentPlugin;
import com.jagent.api.InstrumentationManager;
import com.jagent.api.PluginContext;
import com.jagent.api.PluginDescriptor;
import com.jagent.api.PluginException;
import com.jagent.api.PluginLogger;
import com.jagent.api.lifecycle.ApplicationContextLifecycle;
import com.jagent.plugins.sermantbackend.config.BackendSettings;
import com.jagent.plugins.sermantbackend.event.LogEventBuffer;
import com.jagent.plugins.sermantbackend.event.JulLogHandler;
import com.jagent.plugins.sermantbackend.event.LogbackCapture;
import com.jagent.plugins.sermantbackend.lifecycle.SpringBootLifecycleTransformer;
import com.jagent.plugins.sermantbackend.lifecycle.SpringContextNameListener;
import com.jagent.plugins.sermantbackend.model.AgentIdentity;
import com.jagent.plugins.sermantbackend.service.BackendReporter;

import java.util.logging.Logger;

public final class SermantBackendPlugin implements AgentPlugin {
    private PluginLogger logger;
    private InstrumentationManager instrumentation;
    private BackendSettings settings;
    private AgentIdentity identity;
    private LogEventBuffer eventBuffer;
    private BackendReporter reporter;
    private JulLogHandler julHandler;
    private LogbackCapture logbackCapture;
    private SpringBootLifecycleTransformer transformer;
    private ApplicationContextLifecycle.Registration contextRegistration;
    private String springBootVersionRange;

    @Override public PluginDescriptor descriptor() {
        return new PluginDescriptor("sermant-backend", "Sermant Backend Integration", "0.1.0");
    }

    @Override public void initialize(PluginContext context) throws PluginException {
        logger = context.getLogger();
        instrumentation = context.getInstrumentationManager();
        springBootVersionRange = context.getFrameworkVersionRange("spring-boot");
        try {
            settings = BackendSettings.from(context.getConfiguration());
            identity = new AgentIdentity(settings.getServiceName(), settings.getAppName(), settings.getAppType(), settings.getVersion());
            eventBuffer = new LogEventBuffer(identity, settings.getMaxQueueSize(), settings.getDedupeIntervalMs());
        } catch (RuntimeException error) {
            throw new PluginException("Invalid Sermant Backend configuration: " + error.getMessage(), error);
        }
    }

    @Override public void start() throws PluginException {
        if (!settings.isEnabled()) {
            logger.info("Sermant Backend integration disabled by configuration");
            return;
        }
        logger.info("Sermant Backend settings: gateway=" + settings.getAddress() + ":" + settings.getPort()
                + ", heartbeat=" + settings.getHeartbeatIntervalMs() + "ms, events=" + settings.isEventsEnabled()
                + ", warn=" + settings.isReportWarn() + ", error=" + settings.isReportError());
        try {
            if (settings.isEventsEnabled()) installLogCapture();
            SpringContextNameListener listener = new SpringContextNameListener(identity, logger, springBootVersionRange);
            contextRegistration = ApplicationContextLifecycle.addListener(listener);
            transformer = new SpringBootLifecycleTransformer(logger);
            instrumentation.registerTransformer(transformer, false);
            reporter = new BackendReporter(settings, identity, eventBuffer, logger, descriptor().getVersion());
            reporter.start();
        } catch (Throwable error) {
            stopResources();
            logger.error("Sermant Backend integration startup failed", error);
            throw new PluginException("Cannot start Sermant Backend integration: " + error.getClass().getSimpleName(), error);
        }
    }

    private void installLogCapture() {
        if (settings.isReportWarn() || settings.isReportError()) {
            julHandler = new JulLogHandler(eventBuffer, settings.isReportWarn(), settings.isReportError());
            Logger.getLogger("").addHandler(julHandler);
            logbackCapture = new LogbackCapture(eventBuffer, settings.isReportWarn(), settings.isReportError(), logger);
            logbackCapture.start();
            logger.info("Enabled WARN/ERROR log event capture (bounded queue=" + settings.getMaxQueueSize() + ")");
        }
    }

    @Override public void stop() {
        stopResources();
        logger.info("Sermant Backend integration stopped");
    }

    private void stopResources() {
        if (contextRegistration != null) {
            contextRegistration.close();
            contextRegistration = null;
        }
        if (transformer != null) {
            instrumentation.unregisterTransformer(transformer);
            transformer = null;
        }
        if (logbackCapture != null) {
            logbackCapture.close();
            logbackCapture = null;
        }
        if (julHandler != null) {
            Logger.getLogger("").removeHandler(julHandler);
            julHandler.close();
            julHandler = null;
        }
        if (reporter != null) {
            reporter.close();
            reporter = null;
        }
    }
}
