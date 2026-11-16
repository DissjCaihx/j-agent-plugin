package com.jagent.api;

import com.jagent.api.event.PluginEventBus;
import com.jagent.api.extension.ExtensionRegistry;

public interface PluginContext {
    String getPluginId();

    PluginConfiguration getConfiguration();

    PluginLogger getLogger();

    InstrumentationManager getInstrumentationManager();

    PluginResourceManager getResourceManager();

    PluginEventBus getEventBus();

    ExtensionRegistry getExtensionRegistry();

    /** Returns a descriptor-declared framework version range, if one is present. */
    default String getFrameworkVersionRange(String framework) { return null; }
}
