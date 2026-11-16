package com.jagent.runtime.discovery;

import java.util.Collections;
import java.util.List;

public final class PluginMetadata {
    private final String id;
    private final String name;
    private final String version;
    private final String entryClass;
    private final boolean autoStart;
    private final List<PluginDependency> dependencies;
    private final PluginCompatibility compatibility;

    PluginMetadata(String id, String name, String version, String entryClass, boolean autoStart,
                   List<PluginDependency> dependencies, PluginCompatibility compatibility) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.entryClass = entryClass;
        this.autoStart = autoStart;
        this.dependencies = Collections.unmodifiableList(dependencies);
        this.compatibility = compatibility;
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

    public String getEntryClass() {
        return entryClass;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public List<PluginDependency> getDependencies() {
        return dependencies;
    }

    public PluginCompatibility getCompatibility() { return compatibility; }
}
