package com.jagent.runtime.configuration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AgentConfiguration {
    private final Set<String> enabledPlugins;
    private final String logLevel;

    AgentConfiguration(Set<String> enabledPlugins, String logLevel) {
        this.enabledPlugins = enabledPlugins == null ? null : Collections.unmodifiableSet(new LinkedHashSet<String>(enabledPlugins));
        this.logLevel = logLevel;
    }

    public boolean hasExplicitPluginSelection() {
        return enabledPlugins != null;
    }

    public Set<String> getEnabledPlugins() {
        return enabledPlugins == null ? Collections.<String>emptySet() : enabledPlugins;
    }

    public boolean isEnabled(String pluginId, boolean descriptorDefault) {
        return enabledPlugins == null ? descriptorDefault : enabledPlugins.contains(pluginId);
    }

    public String getLogLevel() {
        return logLevel;
    }

    public static AgentConfiguration defaults() {
        return new AgentConfiguration(null, null);
    }

    public static AgentConfiguration enabledPlugins(Set<String> plugins) {
        return new AgentConfiguration(plugins, null);
    }

    public static AgentConfiguration of(Set<String> plugins, String logLevel) {
        return new AgentConfiguration(plugins, logLevel);
    }
}
