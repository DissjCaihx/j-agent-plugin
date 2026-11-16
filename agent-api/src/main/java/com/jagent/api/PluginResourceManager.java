package com.jagent.api;

public interface PluginResourceManager {
    void register(AutoCloseable resource);
}
