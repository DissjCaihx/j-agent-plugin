package com.jagent.cli;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class AgentCli {
    private AgentCli() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || "help".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return;
        }
        String base = System.getProperty("jagent.url", "http://127.0.0.1:8787");
        String method = "GET", path;
        if ("install".equals(args[0]) && args.length == 2) {
            try {
                System.out.println(upload(base + "/plugins/install", args[1]));
            } catch (Exception e) {
                System.err.println("Plugin install failed: " + e.getMessage());
                System.exit(1);
            }
            return;
        } else if ("update".equals(args[0]) && args.length == 3) {
            try {
                String id = URLEncoder.encode(args[1], "UTF-8");
                System.out.println(upload(base + "/plugins/" + id + "/update", args[2]));
            } catch (Exception e) {
                System.err.println("Plugin update failed: " + e.getMessage());
                System.exit(1);
            }
            return;
        } else if ("list".equals(args[0]) && args.length == 1) path = "/plugins";
        else if (args.length == 2 && ("show".equals(args[0]) || "start".equals(args[0]) || "stop".equals(args[0]) || "reload-config".equals(args[0]))) {
            String id;
            try {
                id = URLEncoder.encode(args[1], "UTF-8");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            path = "/plugins/" + id;
            if ("start".equals(args[0])) {
                path += "/start";
                method = "POST";
            } else if ("stop".equals(args[0])) {
                path += "/stop";
                method = "POST";
            } else if ("reload-config".equals(args[0])) {
                path += "/config/reload";
                method = "POST";
            }
        } else {
            usage();
            System.exit(2);
            return;
        }
        try {
            System.out.println(request(base + path, method));
        } catch (Exception e) {
            System.err.println("Management request failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String request(String address, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        String token = System.getProperty("jagent.token");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("X-Agent-Token", token);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        int status = connection.getResponseCode();
        InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (input != null)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
        connection.disconnect();
        if (status >= 400) throw new IllegalStateException("HTTP " + status + ": " + body.toString().trim());
        return body.toString().trim();
    }

    private static String upload(String address, String file) throws Exception {
        long size = Files.size(Paths.get(file));
        if (size > 128L * 1024L * 1024L) throw new IllegalArgumentException("Plugin archive exceeds 128 MiB");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/zip");
        String token = System.getProperty("jagent.token");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("X-Agent-Token", token);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode((int) size);
        try (InputStream input = Files.newInputStream(Paths.get(file)); OutputStream output = connection.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        int status = connection.getResponseCode();
        InputStream response = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (response != null)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
        connection.disconnect();
        if (status >= 400) throw new IllegalStateException("HTTP " + status + ": " + body.toString().trim());
        return body.toString().trim();
    }

    private static void usage() {
        System.out.println("Usage: java -Djagent.url=http://127.0.0.1:8787 -Djagent.token=TOKEN -jar agent-cli.jar <list|show|start|stop|reload-config|install|update> [plugin-id] [package.zip]");
    }
}
