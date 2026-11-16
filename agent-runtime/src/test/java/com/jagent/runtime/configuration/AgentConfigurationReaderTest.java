package com.jagent.runtime.configuration;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class AgentConfigurationReaderTest {
    @Test
    public void readsExplicitEnabledList() throws Exception {
        Path file = Files.createTempFile("agent", ".yaml");
        Files.write(file, "plugins:\n  enabled:\n    - alpha\n    - beta\n".getBytes(StandardCharsets.UTF_8));
        AgentConfiguration config = new AgentConfigurationReader().read(file);
        assertTrue(config.hasExplicitPluginSelection());
        assertTrue(config.isEnabled("alpha", false));
        assertFalse(config.isEnabled("other", true));
        Files.deleteIfExists(file);
    }

    @Test
    public void readsConfiguredLogLevel() throws Exception {
        Path file = Files.createTempFile("agent-logging", ".yaml");
        Files.write(file, "logging:\n  level: FINE\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("FINE", new AgentConfigurationReader().read(file).getLogLevel());
        Files.deleteIfExists(file);
    }

    @Test
    public void emptyListMeansStartNoneAndMissingFileUsesPluginDefaults() throws Exception {
        Path file = Files.createTempFile("agent-empty", ".yaml");
        Files.write(file, "plugins:\n  enabled: []\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(new AgentConfigurationReader().read(file).isEnabled("alpha", true));
        assertTrue(new AgentConfigurationReader().read(file.resolveSibling("missing-agent.yaml")).isEnabled("alpha", true));
        Files.deleteIfExists(file);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateEnabledIds() throws Exception {
        Path file = Files.createTempFile("agent-duplicate", ".yaml");
        Files.write(file, "plugins:\n  enabled:\n    - alpha\n    - alpha\n".getBytes(StandardCharsets.UTF_8));
        try { new AgentConfigurationReader().read(file); }
        finally { Files.deleteIfExists(file); }
    }
}
