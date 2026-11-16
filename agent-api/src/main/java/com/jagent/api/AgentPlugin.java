package com.jagent.api;

public interface AgentPlugin {
    PluginDescriptor descriptor();

    void initialize(PluginContext context) throws PluginException;

    void start() throws PluginException;

    void stop() throws PluginException;
}
