package com.jagent.runtime.management;

import com.jagent.runtime.manager.PluginRuntime;
import org.junit.Test;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import static org.junit.Assert.*;

public class LocalManagementServerTest {
    @Test
    public void exposesPluginListOnLoopback() throws Exception {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Instrumentation.class }, (proxy, method, args) -> null);
        LocalManagementServer server = new LocalManagementServer(new PluginRuntime(instrumentation), "test-secret");
        try {
            server.start(0);
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort() + "/plugins").openConnection();
            connection.setRequestProperty("X-Agent-Token", "test-secret");
            assertEquals(200, connection.getResponseCode());
            byte[] body = new byte[16];
            int count = connection.getInputStream().read(body);
            assertEquals("[]", new String(body, 0, count, "UTF-8"));
            connection.disconnect();
        } finally { server.close(); }
    }

    @Test
    public void rejectsRequestsWithoutToken() throws Exception {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Instrumentation.class }, (proxy, method, args) -> null);
        LocalManagementServer server = new LocalManagementServer(new PluginRuntime(instrumentation), "test-secret");
        try {
            server.start(0);
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort() + "/plugins").openConnection();
            assertEquals(401, connection.getResponseCode());
            connection.disconnect();
        } finally { server.close(); }
    }
}
