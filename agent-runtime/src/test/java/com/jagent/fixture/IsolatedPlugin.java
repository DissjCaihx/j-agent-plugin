package com.jagent.fixture;

import com.jagent.api.AgentPlugin;
import com.jagent.api.PluginContext;
import com.jagent.api.PluginDescriptor;

public final class IsolatedPlugin implements AgentPlugin {
    public PluginDescriptor descriptor() { return new PluginDescriptor("isolated", "Isolated", "1.0"); }
    public void initialize(PluginContext context) { }
    public void start() { }
    public void stop() { }
}
