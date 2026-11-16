package com.jagent.runtime.discovery;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class PluginMetadataReaderTest {
    @Test
    public void readsValidDescriptorAndDefaultsAutoStart() throws Exception {
        Path file = Files.createTempFile("plugin", ".yaml");
        Files.write(file, ("plugin:\n  id: sample-plugin\n  name: Sample\n  version: 1.2.0\n  entry-class: example.SamplePlugin\n"
                + "lifecycle:\n  auto-start: true\n").getBytes(StandardCharsets.UTF_8));
        PluginMetadata metadata = new PluginMetadataReader().read(file);
        assertEquals("sample-plugin", metadata.getId());
        assertEquals("example.SamplePlugin", metadata.getEntryClass());
        assertTrue(metadata.isAutoStart());
        Files.deleteIfExists(file);
    }

    @Test
    public void rejectsInvalidPluginId() throws Exception {
        Path file = Files.createTempFile("plugin", ".yaml");
        Files.write(file, ("plugin:\n  id: ../bad\n  version: 1\n  entry-class: example.Plugin\n").getBytes(StandardCharsets.UTF_8));
        try {
            new PluginMetadataReader().read(file);
            fail("Expected descriptor validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Invalid plugin.id"));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    public void rejectsMissingRequiredField() throws Exception {
        Path file = Files.createTempFile("plugin", ".yaml");
        Files.write(file, "plugin:\n  id: sample\n".getBytes(StandardCharsets.UTF_8));
        try {
            new PluginMetadataReader().read(file);
            fail("Expected missing field failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Missing plugin.version"));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    public void readsPluginVersionDependencies() throws Exception {
        Path file = Files.createTempFile("plugin-deps", ".yaml");
        Files.write(file, ("plugin:\n  id: consumer\n  version: 1.0\n  entry-class: example.Consumer\n"
                + "dependencies:\n  plugins:\n    - id: provider\n      version: 2.x\n").getBytes(StandardCharsets.UTF_8));
        PluginMetadata metadata = new PluginMetadataReader().read(file);
        assertEquals(1, metadata.getDependencies().size());
        assertEquals("provider", metadata.getDependencies().get(0).getPluginId());
        assertEquals("2.x", metadata.getDependencies().get(0).getVersionRange());
        Files.deleteIfExists(file);
    }
}
