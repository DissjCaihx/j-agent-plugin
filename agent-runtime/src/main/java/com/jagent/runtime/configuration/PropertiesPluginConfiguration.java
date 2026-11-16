package com.jagent.runtime.configuration;

import com.jagent.api.PluginConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class PropertiesPluginConfiguration implements PluginConfiguration {
    private final Properties values = new Properties();

    public PropertiesPluginConfiguration(Path file) throws IOException {
        if (Files.isRegularFile(file)) try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
        }
    }

    public String get(String key) {
        return values.getProperty(key);
    }
}
