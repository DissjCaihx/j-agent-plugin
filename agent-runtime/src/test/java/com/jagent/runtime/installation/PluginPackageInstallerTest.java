package com.jagent.runtime.installation;

import com.jagent.runtime.manager.PluginRuntimeTest;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class PluginPackageInstallerTest {
    @Test
    public void installsAndBacksUpUpdatedPackage() throws Exception {
        Path plugins = Files.createTempDirectory("plugin-install-root");
        PluginPackageInstaller installer = new PluginPackageInstaller(plugins);
        PluginInstallResult first = installer.install(packageZip("1.0"), false, null);
        assertEquals("test-plugin", first.getId());
        assertFalse(first.isUpdated());
        PluginInstallResult second = installer.install(packageZip("2.0"), true, "test-plugin");
        assertTrue(second.isUpdated());
        assertTrue(Files.isRegularFile(plugins.resolve(".pending/test-plugin/plugin.yaml")));
        assertTrue(new String(Files.readAllBytes(plugins.resolve("test-plugin/plugin.yaml")), StandardCharsets.UTF_8).contains("1.0"));
        PluginPackageInstaller.activatePending(plugins);
        assertTrue(Files.isDirectory(plugins.resolve(".backups")));
        assertTrue(new String(Files.readAllBytes(plugins.resolve("test-plugin/plugin.yaml")), StandardCharsets.UTF_8).contains("2.0"));
    }

    @Test
    public void rejectsZipPathTraversal() throws Exception {
        Path plugins = Files.createTempDirectory("plugin-install-traversal");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape")); zip.write("bad".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        try { new PluginPackageInstaller(plugins).install(new ByteArrayInputStream(bytes.toByteArray()), false, null); fail("Expected path traversal rejection"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("escapes")); }
        assertFalse(Files.exists(plugins.getParent().resolve("escape")));
    }

    private static ByteArrayInputStream packageZip(String version) throws Exception {
        ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
        String resource = "/" + PluginRuntimeTest.TestPlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream classBytes = PluginRuntimeTest.TestPlugin.class.getResourceAsStream(resource);
             JarOutputStream jar = new JarOutputStream(jarBytes)) {
            jar.putNextEntry(new JarEntry(resource.substring(1)));
            byte[] buffer = new byte[4096]; int count;
            while ((count = classBytes.read(buffer)) != -1) jar.write(buffer, 0, count);
            jar.closeEntry();
        }
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        String yaml = "plugin:\n  id: test-plugin\n  version: " + version + "\n  entry-class: " + PluginRuntimeTest.TestPlugin.class.getName() + "\n";
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("plugin.yaml")); zip.write(yaml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("plugin.jar")); zip.write(jarBytes.toByteArray()); zip.closeEntry();
        }
        return new ByteArrayInputStream(zipBytes.toByteArray());
    }
}
