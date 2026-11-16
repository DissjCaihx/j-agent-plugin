package com.jagent.runtime.management;

import com.jagent.runtime.manager.PluginInfo;
import com.jagent.runtime.manager.PluginRuntime;
import com.jagent.runtime.installation.PluginInstallResult;
import com.jagent.runtime.logging.RuntimeLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class LocalManagementServer implements AutoCloseable {
    private final PluginRuntime runtime;
    private final byte[] token;
    private HttpServer server;

    public LocalManagementServer(PluginRuntime runtime, String token) {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Management token is required");
        this.runtime = runtime;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void start(int port) throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/plugins", this::handlePlugins);
        server.setExecutor(null);
        server.start();
    }

    public synchronized int getPort() {
        if (server == null) throw new IllegalStateException("Management server is not running");
        return server.getAddress().getPort();
    }

    private void handlePlugins(HttpExchange exchange) throws IOException {
        try {
            String provided = exchange.getRequestHeaders().getFirst("X-Agent-Token");
            if (provided == null || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length == 3 && "plugins".equals(parts[1]) && "install".equals(parts[2]) && "POST".equals(exchange.getRequestMethod())) {
                PluginInstallResult result = runtime.installPluginPackage(new LimitedInputStream(exchange.getRequestBody(), 128L * 1024L * 1024L));
                send(exchange, 201, installJson(result));
                return;
            }
            if (parts.length == 4 && "plugins".equals(parts[1]) && "update".equals(parts[3]) && "POST".equals(exchange.getRequestMethod())) {
                PluginInstallResult result = runtime.updatePluginPackage(parts[2], new LimitedInputStream(exchange.getRequestBody(), 128L * 1024L * 1024L));
                send(exchange, 200, installJson(result));
                return;
            }
            if (parts.length == 2 && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, listJson(runtime.plugins()));
                return;
            }
            if (parts.length == 3 && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, pluginJson(runtime.getPlugin(parts[2])));
                return;
            }
            if (parts.length == 4 && "POST".equals(exchange.getRequestMethod())) {
                PluginInfo info;
                if ("start".equals(parts[3])) info = runtime.startPlugin(parts[2]);
                else if ("stop".equals(parts[3])) info = runtime.stopPlugin(parts[2]);
                else {
                    send(exchange, 404, "{\"error\":\"not found\"}");
                    return;
                }
                send(exchange, 200, pluginJson(info));
                return;
            }
            if (parts.length == 5 && "POST".equals(exchange.getRequestMethod())
                    && "config".equals(parts[3]) && "reload".equals(parts[4])) {
                send(exchange, 200, pluginJson(runtime.reloadPluginConfiguration(parts[2])));
                return;
            }
            send(exchange, 404, "{\"error\":\"not found\"}");
        } catch (IllegalArgumentException e) {
            RuntimeLog.warn("management", e.getMessage());
            send(exchange, 404, errorJson(e));
        } catch (IllegalStateException e) {
            RuntimeLog.warn("management", e.getMessage());
            send(exchange, 409, errorJson(e));
        } catch (IOException e) {
            RuntimeLog.error("management", "Management request failed", e);
            send(exchange, 400, errorJson(e));
        } catch (RuntimeException e) {
            RuntimeLog.error("management", "Unhandled management request failure", e);
            send(exchange, 500, errorJson(e));
        }
    }

    private static String listJson(Collection<PluginInfo> plugins) {
        StringBuilder json = new StringBuilder("[");
        for (PluginInfo plugin : plugins) {
            if (json.length() > 1) json.append(',');
            json.append(pluginJson(plugin));
        }
        return json.append(']').toString();
    }

    private static String pluginJson(PluginInfo p) {
        return "{\"id\":\"" + escape(p.getId()) + "\",\"name\":\"" + escape(p.getName()) + "\",\"version\":\"" + escape(p.getVersion()) + "\",\"state\":\"" + p.getState() + "\",\"failure\":" + (p.getFailure() == null ? "null" : "\"" + escape(p.getFailure()) + "\"") + "}";
    }

    private static String installJson(PluginInstallResult r) {
        return "{\"id\":\"" + escape(r.getId()) + "\",\"version\":\"" + escape(r.getVersion()) + "\",\"updated\":" + r.isUpdated() + ",\"restartRequired\":true}";
    }

    private static String errorJson(Exception e) {
        return "{\"error\":\"" + escape(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long limit;
        private long read;

        LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > limit) throw new IOException("Plugin upload exceeds size limit");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, (int) Math.min(length, limit - read + 1));
            if (count > 0 && (read += count) > limit) throw new IOException("Plugin upload exceeds size limit");
            return count;
        }
    }
}
