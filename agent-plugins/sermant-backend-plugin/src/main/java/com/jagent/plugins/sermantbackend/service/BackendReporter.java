package com.jagent.plugins.sermantbackend.service;

import com.jagent.api.PluginLogger;
import com.jagent.plugins.sermantbackend.config.BackendSettings;
import com.jagent.plugins.sermantbackend.event.BackendLogEvent;
import com.jagent.plugins.sermantbackend.event.LogEventBuffer;
import com.jagent.plugins.sermantbackend.model.AgentIdentity;
import com.jagent.plugins.sermantbackend.transport.SermantGatewayClient;
import com.jagent.plugins.sermantbackend.transport.SermantPayloads;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class BackendReporter implements AutoCloseable {
    private static final int HEARTBEAT_DATA = 0;
    private static final int EVENT_DATA = 1;
    private static final int MAX_BATCH_SIZE = 100;
    private final BackendSettings settings;
    private final AgentIdentity identity;
    private final LogEventBuffer events;
    private final SermantGatewayClient client;
    private final PluginLogger logger;
    private final String pluginVersion;
    private ScheduledExecutorService scheduler;
    private long lastReportedDropped;

    public BackendReporter(BackendSettings settings, AgentIdentity identity, LogEventBuffer events,
                           PluginLogger logger, String pluginVersion) {
        this.settings = settings; this.identity = identity; this.events = events; this.logger = logger;
        this.pluginVersion = pluginVersion;
        this.client = new SermantGatewayClient(settings.getAddress(), settings.getPort(), settings.getConnectTimeoutMs(),
                settings.getReconnectIntervalMs(), logger);
    }

    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int number;
            @Override public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "j-agent-sermant-reporter-" + (++number));
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { sendHeartbeat(); }
        }, 1000, settings.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        if (settings.isEventsEnabled()) scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { flushEvents(); }
        }, settings.getEventIntervalMs(), settings.getEventIntervalMs(), TimeUnit.MILLISECONDS);
        logger.info("Sermant Backend reporter started: service=" + identity.getServiceName() + ", app="
                + identity.getAppName() + ", address=" + settings.getAddress() + ":" + settings.getPort()
                + ", heartbeat=" + settings.getHeartbeatIntervalMs() + "ms, events=" + settings.isEventsEnabled());
    }

    private void sendHeartbeat() {
        try {
            long now = System.currentTimeMillis();
            client.send(HEARTBEAT_DATA, SermantPayloads.heartbeat(identity, now, pluginVersion));
        } catch (Throwable error) {
            logger.error("Could not create Sermant Backend heartbeat", error);
        }
    }

    private void flushEvents() {
        long dropped = events.getDroppedCount();
        if (dropped > lastReportedDropped) {
            logger.warn("Sermant Backend log event queue is full; dropped " + (dropped - lastReportedDropped)
                    + " log events (total=" + dropped + ")");
            lastReportedDropped = dropped;
        }
        List<BackendLogEvent> batch = events.drain(MAX_BATCH_SIZE);
        if (batch.isEmpty()) return;
        if (!client.send(EVENT_DATA, SermantPayloads.events(identity, batch))) {
            // Preserve failed events in memory for the next retry, bounded by configured queue capacity.
            for (BackendLogEvent event : batch) events.requeue(event);
        }
    }

    @Override public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (settings.isEventsEnabled()) {
            List<BackendLogEvent> batch;
            do {
                batch = events.drain(MAX_BATCH_SIZE);
                if (batch.isEmpty()) break;
                if (!client.send(EVENT_DATA, SermantPayloads.events(identity, batch))) break;
            } while (events.size() > 0);
        }
        client.close();
    }
}
