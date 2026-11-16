package com.jagent.runtime.configuration;

import com.jagent.api.PluginConfiguration;

import java.util.concurrent.atomic.AtomicReference;

public final class ReloadablePluginConfiguration implements PluginConfiguration {
    private final AtomicReference<PluginConfiguration> current;

    public ReloadablePluginConfiguration(PluginConfiguration initial) {
        current = new AtomicReference<PluginConfiguration>(initial);
    }

    public String get(String key) {
        return current.get().get(key);
    }

    public void replace(PluginConfiguration configuration) {
        current.set(configuration);
    }
}
