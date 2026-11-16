package com.jagent.api.lifecycle;

import java.util.concurrent.CopyOnWriteArrayList;

/** Generic bridge for optional framework adapters to publish application lifecycle objects. */
public final class ApplicationContextLifecycle {
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    private ApplicationContextLifecycle() { }

    public static Registration addListener(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        LISTENERS.addIfAbsent(listener);
        return new Registration(listener);
    }

    /** Called by instrumented framework lifecycle methods. Listener failures never escape into the host app. */
    public static void contextStarted(Object context) {
        if (context == null) return;
        for (Listener listener : LISTENERS) {
            try { listener.onStarted(context); } catch (Throwable error) { notifyFailure(listener, context, error); }
        }
    }

    /** Called after the framework stops its web server. */
    public static void contextStopped(Object context) {
        if (context == null) return;
        for (Listener listener : LISTENERS) {
            try { listener.onStopped(context); } catch (Throwable error) { notifyFailure(listener, context, error); }
        }
    }

    private static void notifyFailure(Listener listener, Object context, Throwable error) {
        try { listener.onFailure(context, error); } catch (Throwable ignored) { }
    }

    public interface Listener {
        void onStarted(Object context);
        void onStopped(Object context);
        default void onFailure(Object context, Throwable error) { }
    }

    public static final class Registration implements AutoCloseable {
        private final Listener listener;
        private volatile boolean closed;

        private Registration(Listener listener) { this.listener = listener; }

        @Override public void close() {
            if (!closed) {
                closed = true;
                LISTENERS.remove(listener);
            }
        }
    }
}
