package com.jagent.runtime.event;

import com.jagent.api.event.PluginEventBus;
import com.jagent.runtime.logging.RuntimeLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class PluginEventBusImpl implements PluginEventBus {
    private final List<Listener<?>> listeners = new ArrayList<Listener<?>>();

    public synchronized <T> Subscription subscribe(String pluginId, Class<T> type, EventHandler<T> handler) {
        if (pluginId == null || type == null || handler == null)
            throw new IllegalArgumentException("Subscription values must not be null");
        final Listener<T> listener = new Listener<T>(pluginId, type, handler);
        listeners.add(listener);
        return new Subscription() {
            public void close() {
                synchronized (PluginEventBusImpl.this) {
                    listeners.remove(listener);
                }
            }
        };
    }

    public void publish(Object event) {
        if (event == null) throw new IllegalArgumentException("Event must not be null");
        List<Listener<?>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<Listener<?>>(listeners);
        }
        for (Listener<?> listener : snapshot) if (listener.type.isInstance(event)) listener.dispatch(event);
    }

    public synchronized void unsubscribeAll(String pluginId) {
        for (Iterator<Listener<?>> it = listeners.iterator(); it.hasNext(); )
            if (it.next().pluginId.equals(pluginId)) it.remove();
    }

    private static final class Listener<T> {
        final String pluginId;
        final Class<T> type;
        final EventHandler<T> handler;

        Listener(String pluginId, Class<T> type, EventHandler<T> handler) {
            this.pluginId = pluginId;
            this.type = type;
            this.handler = handler;
        }

        void dispatch(Object event) {
            try {
                handler.onEvent(type.cast(event));
            } catch (Throwable error) {
                RuntimeLog.error("plugin:" + pluginId, "Event handler failed", error);
            }
        }
    }
}
