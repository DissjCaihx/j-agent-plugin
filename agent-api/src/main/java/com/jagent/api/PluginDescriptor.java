package com.jagent.api;

public final class PluginDescriptor {
    private final String id;
    private final String name;
    private final String version;

    public PluginDescriptor(String id, String name, String version) {
        this.id = id;
        this.name = name;
        this.version = version;
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
}
