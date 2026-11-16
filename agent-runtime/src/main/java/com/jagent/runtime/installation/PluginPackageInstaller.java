package com.jagent.runtime.installation;

import com.jagent.api.AgentPlugin;
import com.jagent.runtime.classloader.PluginClassLoader;
import com.jagent.runtime.discovery.PluginMetadata;
import com.jagent.runtime.discovery.PluginMetadataReader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PluginPackageInstaller {
    private static final long MAX_EXPANDED_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 512;
    private final Path pluginRoot;

    public PluginPackageInstaller(Path pluginRoot) {
        this.pluginRoot = pluginRoot.toAbsolutePath().normalize();
    }

    public synchronized PluginInstallResult install(InputStream archive, boolean update, String expectedId) throws IOException {
        Files.createDirectories(pluginRoot);
        Path staging = Files.createTempDirectory(pluginRoot, ".install-");
        Path backup = null;
        try {
            extract(archive, staging);
            PluginMetadata metadata = new PluginMetadataReader().read(staging.resolve("plugin.yaml"));
            if (expectedId != null && !expectedId.equals(metadata.getId()))
                throw new IllegalArgumentException("Package plugin id does not match requested id");
            validateEntryClass(staging, metadata);
            Path target = pluginRoot.resolve(metadata.getId()).normalize();
            if (!target.getParent().equals(pluginRoot)) throw new IllegalArgumentException("Invalid plugin id path");
            boolean exists = Files.exists(target);
            if (exists && !update)
                throw new IllegalStateException("Plugin already exists; use update: " + metadata.getId());
            if (!exists && update) throw new IllegalArgumentException("Plugin is not installed: " + metadata.getId());
            if (exists) {
                Path pendingRoot = pluginRoot.resolve(".pending");
                Files.createDirectories(pendingRoot);
                Path pendingTarget = pendingRoot.resolve(metadata.getId());
                if (Files.exists(pendingTarget)) {
                    backup = pluginRoot.resolve(".backups").resolve(metadata.getId() + "-pending-" + System.currentTimeMillis());
                    Files.createDirectories(backup.getParent());
                    move(pendingTarget, backup);
                }
                move(staging, pendingTarget);
                return new PluginInstallResult(metadata.getId(), metadata.getVersion(), true);
            }
            move(staging, target);
            return new PluginInstallResult(metadata.getId(), metadata.getVersion(), false);
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    public static synchronized void activatePending(Path pluginRoot) throws IOException {
        Path root = pluginRoot.toAbsolutePath().normalize();
        Path pendingRoot = root.resolve(".pending");
        if (!Files.isDirectory(pendingRoot)) return;
        List<Path> packages = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pendingRoot)) {
            for (Path path : stream) if (Files.isDirectory(path)) packages.add(path);
        }
        java.util.Collections.sort(packages);
        for (Path pending : packages) {
            PluginMetadata metadata = new PluginMetadataReader().read(pending.resolve("plugin.yaml"));
            if (!pending.getFileName().toString().equals(metadata.getId()))
                throw new IOException("Pending package directory does not match plugin id: " + pending);
            validateEntryClass(pending, metadata);
            Path target = root.resolve(metadata.getId());
            Path backup = null;
            if (Files.exists(target)) {
                Path backupRoot = root.resolve(".backups");
                Files.createDirectories(backupRoot);
                backup = backupRoot.resolve(metadata.getId() + "-" + System.currentTimeMillis());
                move(target, backup);
            }
            try {
                move(pending, target);
            } catch (IOException failure) {
                if (backup != null && Files.exists(backup)) move(backup, target);
                throw failure;
            }
        }
    }

    private static void extract(InputStream archive, Path staging) throws IOException {
        Set<String> names = new HashSet<String>();
        long expanded = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("Plugin package has too many entries");
                String name = entry.getName();
                if (name.indexOf('\\') >= 0 || name.startsWith("/") || name.matches("^[A-Za-z]:.*") || !names.add(name))
                    throw new IOException("Unsafe or duplicate ZIP entry: " + name);
                Path output = staging.getFileSystem().getPath(name).normalize();
                if (output.isAbsolute() || output.startsWith(".."))
                    throw new IOException("ZIP entry escapes package directory: " + name);
                Path destination = staging.resolve(output).normalize();
                if (!destination.startsWith(staging))
                    throw new IOException("ZIP entry escapes package directory: " + name);
                if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    try (java.io.OutputStream out = Files.newOutputStream(destination)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            expanded += n;
                            if (expanded > MAX_EXPANDED_BYTES)
                                throw new IOException("Plugin package expands beyond size limit");
                            out.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void validateEntryClass(Path directory, PluginMetadata metadata) throws IOException {
        List<URL> urls = new ArrayList<URL>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : jars) urls.add(jar.toUri().toURL());
        }
        Path lib = directory.resolve("lib");
        if (Files.isDirectory(lib)) try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "*.jar")) {
            for (Path jar : jars) urls.add(jar.toUri().toURL());
        }
        if (urls.isEmpty()) throw new IOException("Plugin package contains no JAR");
        try (PluginClassLoader loader = new PluginClassLoader(urls.toArray(new URL[urls.size()]), PluginPackageInstaller.class.getClassLoader())) {
            try {
                loader.loadClass(metadata.getEntryClass()).asSubclass(AgentPlugin.class);
            } catch (ClassNotFoundException | ClassCastException e) {
                throw new IOException("Invalid plugin entry class: " + metadata.getEntryClass(), e);
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : ordered) Files.deleteIfExists(path);
        }
    }
}
