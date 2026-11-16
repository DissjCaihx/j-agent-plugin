package com.jagent.api.event;

public interface PluginEventBus {
    <T> Subscription subscribe(String pluginId, Class<T> eventType, EventHandler<T> handler);

    void publish(Object event);

    void unsubscribeAll(String pluginId);

    interface EventHandler<T> {
        void onEvent(T event) throws Exception;
    }

    interface Subscription extends AutoCloseable {
        void close();
    }
}
