package com.jagent.api.extension;

import java.util.List;

public interface ExtensionRegistry {
    <T> void register(String pluginId, Class<T> extensionType, T extension);

    <T> T get(Class<T> extensionType);

    <T> List<T> getAll(Class<T> extensionType);

    void unregisterAll(String pluginId);
}
