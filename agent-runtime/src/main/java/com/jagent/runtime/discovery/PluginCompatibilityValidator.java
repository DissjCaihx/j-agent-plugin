package com.jagent.runtime.discovery;

import com.jagent.api.version.VersionRange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Validates the host Java/API and private libraries declared in a plugin descriptor. */
public final class PluginCompatibilityValidator {
    private static final String CORE_API_VERSION = "0.1.0-SNAPSHOT";
    private PluginCompatibilityValidator() { }

    public static void validateRuntime(PluginMetadata metadata, Path pluginDirectory) throws IOException {
        PluginCompatibility compatibility = metadata.getCompatibility();
        String javaRange = compatibility.getJavaVersionRange();
        if (javaRange != null) {
            String javaVersion = System.getProperty("java.specification.version", "");
            if (javaVersion.startsWith("1.")) javaVersion = javaVersion.substring(2);
            if (!VersionRange.includes(javaVersion, javaRange))
                throw new IllegalStateException("Plugin " + metadata.getId() + " supports Java " + javaRange
                        + " but current runtime is " + javaVersion);
        }
        String apiRange = compatibility.getCoreApiRange();
        if (apiRange != null) {
            String apiVersion = System.getProperty("jagent.core-api.version", CORE_API_VERSION);
            if (!VersionRange.includes(apiVersion, apiRange))
                throw new IllegalStateException("Plugin " + metadata.getId() + " supports Agent API " + apiRange
                        + " but current API is " + apiVersion);
        }
        Map<String, String> privateLibraries = readPrivateLibraries(pluginDirectory);
        for (Map.Entry<String, String> requirement : compatibility.getLibraryRanges().entrySet()) {
            String actual = privateLibraries.get(requirement.getKey());
            if (actual == null)
                throw new IllegalStateException("Plugin " + metadata.getId() + " declares library "
                        + requirement.getKey() + " " + requirement.getValue() + " but that library was not found in its package");
            if (!VersionRange.includes(actual, requirement.getValue()))
                throw new IllegalStateException("Plugin " + metadata.getId() + " requires " + requirement.getKey()
                        + " " + requirement.getValue() + " but package contains " + actual);
        }
        for (Map.Entry<String, String> requirement : compatibility.getFrameworkRanges().entrySet()) {
            String actual = detectFrameworkVersion(requirement.getKey());
            if (actual != null && !VersionRange.includes(actual, requirement.getValue()))
                throw new IllegalStateException("Plugin " + metadata.getId() + " supports framework "
                        + requirement.getKey() + " " + requirement.getValue() + " but found " + actual);
        }
    }

    private static String detectFrameworkVersion(String framework) {
        String configured = System.getProperty("jagent.framework." + framework + ".version");
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        if (!"spring-boot".equals(framework)) return null;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> versionClass = Class.forName("org.springframework.boot.SpringBootVersion", false, loader);
            Object version = versionClass.getMethod("getVersion").invoke(null);
            return version == null ? null : String.valueOf(version);
        } catch (Throwable unavailableBeforeApplicationStartup) {
            return null;
        }
    }

    private static Map<String, String> readPrivateLibraries(Path directory) throws IOException {
        Map<String, String> versions = new HashMap<String, String>();
        readJars(directory, versions);
        Path lib = directory.resolve("lib");
        if (Files.isDirectory(lib)) readJars(lib, versions);
        return versions;
    }

    private static void readJars(Path directory, Map<String, String> versions) throws IOException {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path path : jars) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    java.util.Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!entry.isDirectory() && name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                            Properties properties = new Properties();
                            try (InputStream input = jar.getInputStream(entry)) { properties.load(input); }
                            String group = properties.getProperty("groupId");
                            String artifact = properties.getProperty("artifactId");
                            String version = properties.getProperty("version");
                            if (group != null && artifact != null && version != null)
                                versions.put(group + ":" + artifact, version);
                        }
                    }
                } catch (IOException ignored) {
                    // Non-JAR or damaged optional entries are reported as missing when explicitly required.
                }
            }
        }
    }
}
