package com.jagent.runtime.configuration;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

public final class AgentConfigurationReader {
    public AgentConfiguration read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return AgentConfiguration.defaults();
        Object value;
        try (InputStream input = Files.newInputStream(file)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            value = new Yaml(new SafeConstructor(options)).load(input);
        }
        if (!(value instanceof Map)) throw new IllegalArgumentException("agent.yaml root must be a mapping");
        Map<?, ?> root = (Map<?, ?>) value;
        Object pluginsValue = root.get("plugins");
        Set<String> enabled = null;
        if (pluginsValue != null) {
            if (!(pluginsValue instanceof Map)) throw new IllegalArgumentException("plugins must be a mapping");
            Object enabledValue = ((Map<?, ?>) pluginsValue).get("enabled");
            if (enabledValue != null) {
                if (!(enabledValue instanceof List))
                    throw new IllegalArgumentException("plugins.enabled must be a list");
                enabled = new LinkedHashSet<String>();
                for (Object idValue : (List<?>) enabledValue) {
                    if (!(idValue instanceof String))
                        throw new IllegalArgumentException("plugins.enabled entries must be strings");
                    String id = ((String) idValue).trim();
                    if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                        throw new IllegalArgumentException("Invalid enabled plugin id: " + id);
                    if (!enabled.add(id)) throw new IllegalArgumentException("Duplicate enabled plugin id: " + id);
                }
            }
        }
        String logLevel = null;
        Object loggingValue = root.get("logging");
        if (loggingValue != null) {
            if (!(loggingValue instanceof Map)) throw new IllegalArgumentException("logging must be a mapping");
            Object level = ((Map<?, ?>) loggingValue).get("level");
            if (level != null) {
                logLevel = String.valueOf(level).trim();
                if (logLevel.isEmpty()) throw new IllegalArgumentException("logging.level must not be empty");
            }
        }
        return AgentConfiguration.of(enabled, logLevel);
    }
}
