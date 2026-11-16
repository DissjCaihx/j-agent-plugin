package com.jagent.api.configuration;

import com.jagent.api.PluginConfiguration;
import com.jagent.api.PluginException;

/**
 * Opt-in contract for plugins that can apply configuration changes without restart.
 */
public interface ConfigurablePlugin {
    void onConfigurationChanged(PluginConfiguration configuration) throws PluginException;
}
