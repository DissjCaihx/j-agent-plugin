package com.jagent.runtime.discovery;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import com.jagent.api.version.VersionRange;

public final class PluginMetadataReader {
    public PluginMetadata read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Missing plugin descriptor: " + file);
        Map<?, ?> root;
        try (InputStream input = Files.newInputStream(file)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Descriptor root must be a mapping");
            root = (Map<?, ?>) loaded;
        }
        Map<?, ?> plugin = section(root, "plugin");
        String id = required(plugin, "id");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid plugin.id: " + id);
        String version = required(plugin, "version");
        String entryClass = required(plugin, "entry-class");
        if (!entryClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("Invalid plugin.entry-class: " + entryClass);
        Map<?, ?> lifecycle = optionalSection(root, "lifecycle");
        Object autoStartValue = lifecycle.get("auto-start");
        boolean autoStart = autoStartValue == null || Boolean.parseBoolean(String.valueOf(autoStartValue));
        Object nameValue = plugin.get("name");
        String name = nameValue == null ? id : String.valueOf(nameValue);
        List<PluginDependency> dependencies = readDependencies(root);
        PluginCompatibility compatibility = readCompatibility(root);
        return new PluginMetadata(id, name, version, entryClass, autoStart, dependencies, compatibility);
    }

    private static PluginCompatibility readCompatibility(Map<?, ?> root) {
        Map<?, ?> runtime = optionalSection(root, "runtime");
        String javaRange = optionalRange(runtime.get("java-version"), "runtime.java-version");
        Map<?, ?> compatibility = optionalSection(root, "compatibility");
        String coreApi = optionalRange(compatibility.get("core-api"), "compatibility.core-api");
        Map<String, String> frameworks = rangeMap(compatibility.get("frameworks"), "compatibility.frameworks");
        Map<String, String> libraries = rangeMap(compatibility.get("libraries"), "compatibility.libraries");
        return new PluginCompatibility(javaRange, coreApi, frameworks, libraries);
    }

    private static Map<String, String> rangeMap(Object value, String path) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value == null) return result;
        if (!(value instanceof Map)) throw new IllegalArgumentException(path + " must be a mapping");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            if (name.isEmpty()) throw new IllegalArgumentException(path + " contains an empty dependency name");
            result.put(name, requiredRange(entry.getValue(), path + "." + name));
        }
        return result;
    }

    private static String optionalRange(Object value, String path) {
        return value == null ? null : requiredRange(value, path);
    }

    private static String requiredRange(Object value, String path) {
        String range = String.valueOf(value).trim();
        try { VersionRange.validate(range); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid " + path + " version range: " + range, error); }
        return range;
    }

    private static List<PluginDependency> readDependencies(Map<?, ?> root) {
        List<PluginDependency> dependencies = new ArrayList<PluginDependency>();
        Object dependencyRoot = root.get("dependencies");
        if (dependencyRoot == null) return dependencies;
        if (!(dependencyRoot instanceof Map)) throw new IllegalArgumentException("dependencies must be a mapping");
        Object pluginsValue = ((Map<?, ?>) dependencyRoot).get("plugins");
        if (pluginsValue == null) return dependencies;
        if (!(pluginsValue instanceof List)) throw new IllegalArgumentException("dependencies.plugins must be a list");
        for (Object item : (List<?>) pluginsValue) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Each plugin dependency must be a mapping");
            Map<?, ?> dependency = (Map<?, ?>) item;
            Object idValue = dependency.get("id");
            Object versionValue = dependency.get("version");
            if (idValue == null || versionValue == null)
                throw new IllegalArgumentException("Plugin dependency requires id and version");
            String id = String.valueOf(idValue).trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                throw new IllegalArgumentException("Invalid dependency plugin id: " + id);
            String range = requiredRange(versionValue, "dependencies.plugins[" + id + "]");
            dependencies.add(new PluginDependency(id, range));
        }
        return dependencies;
    }

    private static Map<?, ?> section(Map<?, ?> parent, String name) {
        Map<?, ?> result = optionalSection(parent, name);
        if (result.isEmpty()) throw new IllegalArgumentException("Missing or invalid section: " + name);
        return result;
    }

    private static Map<?, ?> optionalSection(Map<?, ?> parent, String name) {
        Object value = parent.get(name);
        if (value == null) return java.util.Collections.emptyMap();
        if (!(value instanceof Map)) throw new IllegalArgumentException("Section must be a mapping: " + name);
        return (Map<?, ?>) value;
    }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty())
            throw new IllegalArgumentException("Missing plugin." + key);
        return String.valueOf(value).trim();
    }
}
