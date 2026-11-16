package com.jagent.runtime.manager;

import com.jagent.runtime.lifecycle.PluginState;

public final class PluginInfo {
    private final String id;
    private final String name;
    private final String version;
    private final PluginState state;
    private final String failure;

    PluginInfo(String id, String name, String version, PluginState state, String failure) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.state = state;
        this.failure = failure;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public PluginState getState() {
        return state;
    }

    public String getFailure() {
        return failure;
    }
}
