package com.jagent.plugins.sermantbackend.transport;

import com.jagent.api.PluginLogger;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Persistent TCP sender compatible with Sermant Backend's Netty gateway. */
public final class SermantGatewayClient implements Closeable {
    private final String address;
    private final int port;
    private final int connectTimeoutMs;
    private final int reconnectIntervalMs;
    private final PluginLogger logger;
    private Socket socket;
    private OutputStream output;
    private volatile boolean closed;
    private volatile long nextConnectTime;

    public SermantGatewayClient(String address, int port, int connectTimeoutMs, int reconnectIntervalMs, PluginLogger logger) {
        this.address = address;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.logger = logger;
    }

    public synchronized boolean send(int dataType, String json) {
        if (closed) return false;
        if ((socket == null || socket.isClosed()) && System.currentTimeMillis() < nextConnectTime) return false;
        try {
            ensureConnected();
            output.write(SermantMessageCodec.frame(dataType, json));
            output.flush();
            nextConnectTime = 0;
            return true;
        } catch (Exception error) {
            disconnect();
            nextConnectTime = System.currentTimeMillis() + reconnectIntervalMs;
            logger.warn("Sermant Backend send failed at " + address + ":" + port + ": " + safe(error));
            return false;
        }
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected() && !socket.isClosed() && !socket.isOutputShutdown()) return;
        disconnect();
        Socket candidate = new Socket();
        candidate.setTcpNoDelay(true);
        candidate.connect(new InetSocketAddress(address, port), connectTimeoutMs);
        socket = candidate;
        output = new BufferedOutputStream(candidate.getOutputStream());
        logger.info("Connected to Sermant Backend gateway " + address + ":" + port);
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void disconnect() {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        socket = null;
        output = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        disconnect();
    }
}
