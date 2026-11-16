package com.jagent.runtime.discovery;

public final class PluginDependency {
    private final String pluginId;
    private final String versionRange;

    public PluginDependency(String pluginId, String versionRange) {
        this.pluginId = pluginId;
        this.versionRange = versionRange;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getVersionRange() {
        return versionRange;
    }
}
