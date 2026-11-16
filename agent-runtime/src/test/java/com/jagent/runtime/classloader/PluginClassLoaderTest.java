package com.jagent.runtime.classloader;

import com.jagent.api.AgentPlugin;
import com.jagent.fixture.IsolatedPlugin;
import org.junit.Test;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.Assert.*;

public class PluginClassLoaderTest {
    @Test
    public void isolatesPluginClassesAndSharesApiIdentity() throws Exception {
        Path jar = Files.createTempFile("isolated-plugin", ".jar");
        String resource = "/" + IsolatedPlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream input = IsolatedPlugin.class.getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resource.substring(1)));
            byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.closeEntry();
        }
        URL[] urls = new URL[] { jar.toUri().toURL() };
        try (PluginClassLoader first = new PluginClassLoader(urls, getClass().getClassLoader());
             PluginClassLoader second = new PluginClassLoader(urls, getClass().getClassLoader())) {
            Class<?> firstPlugin = first.loadClass(IsolatedPlugin.class.getName());
            Class<?> secondPlugin = second.loadClass(IsolatedPlugin.class.getName());
            assertNotSame(firstPlugin, secondPlugin);
            assertTrue(AgentPlugin.class.isAssignableFrom(firstPlugin));
            assertSame(AgentPlugin.class, firstPlugin.getInterfaces()[0]);
        } finally { Files.deleteIfExists(jar); }
    }
}
