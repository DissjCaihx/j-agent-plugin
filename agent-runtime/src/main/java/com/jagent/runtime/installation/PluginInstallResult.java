package com.jagent.runtime.installation;

public final class PluginInstallResult {
    private final String id;
    private final String version;
    private final boolean updated;

    PluginInstallResult(String id, String version, boolean updated) {
        this.id = id;
        this.version = version;
        this.updated = updated;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public boolean isUpdated() {
        return updated;
    }
}
