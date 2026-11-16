package com.jagent.plugins.sermantbackend.transport;

import com.jagent.plugins.sermantbackend.event.BackendLogEvent;
import com.jagent.plugins.sermantbackend.model.AgentIdentity;

import java.util.List;

public final class SermantPayloads {
    private SermantPayloads() { }

    public static String heartbeat(AgentIdentity id, long now, String pluginVersion) {
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        property(out, "service", id.getServiceName()).append(',');
        property(out, "appType", id.getAppType()).append(',');
        property(out, "hostName", id.getHostName()).append(',');
        out.append("\"ip\":"); strings(out, id.getIps()); out.append(',');
        number(out, "heartbeatTime", now).append(',');
        number(out, "lastHeartbeatTime", now).append(',');
        property(out, "version", id.getVersion()).append(',');
        property(out, "instanceId", id.getInstanceId()).append(',');
        property(out, "appName", id.getAppName()).append(',');
        property(out, "artifact", id.getAppName()).append(',');
        property(out, "processId", id.getProcessId()).append(',');
        out.append("\"dynamicInstall\":false,");
        out.append("\"pluginInfoMap\":[{\"name\":\"j-agent-plugin\",\"version\":");
        quote(out, pluginVersion).append("}]");
        out.append('}');
        return out.toString();
    }

    public static String events(AgentIdentity identity, List<BackendLogEvent> events) {
        StringBuilder out = new StringBuilder(events.size() * 256 + 96);
        out.append("{\"metaHash\":"); quote(out, identity.getInstanceId()); out.append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) out.append(',');
            BackendLogEvent event = events.get(i);
            out.append('{');
            property(out, "metaHash", identity.getInstanceId()).append(',');
            number(out, "time", event.getTime()).append(',');
            property(out, "eventLevel", event.getLevel()).append(',');
            property(out, "eventType", "LOG").append(',');
            out.append("\"logInfo\":{");
            property(out, "logLevel", event.getLogLevel()).append(',');
            property(out, "logMessage", event.getMessage()).append(',');
            property(out, "logClass", event.getClassName()).append(',');
            property(out, "logMethod", event.getMethodName()).append(',');
            out.append("\"logLineNumber\":").append(event.getLineNumber()).append(',');
            out.append("\"logThreadId\":").append(event.getThreadId()).append(',');
            property(out, "logThrowable", event.getThrowable());
            out.append("}}");
        }
        out.append("]}");
        return out.toString();
    }

    private static StringBuilder property(StringBuilder out, String key, String value) {
        quote(out, key).append(':'); return quote(out, value);
    }
    private static StringBuilder number(StringBuilder out, String key, long value) {
        quote(out, key).append(':').append(value); return out;
    }
    private static void strings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            quote(out, values.get(i));
        }
        out.append(']');
    }
    private static StringBuilder quote(StringBuilder out, String value) {
        if (value == null) return out.append("null");
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"');
    }
}
