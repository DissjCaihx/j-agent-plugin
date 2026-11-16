package com.jagent.plugins.sermantbackend.event;

import com.jagent.plugins.sermantbackend.model.AgentIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LogEventBuffer {
    private final AgentIdentity identity;
    private final ArrayBlockingQueue<BackendLogEvent> queue;
    private final long dedupeIntervalMs;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<String, Long>();
    private final AtomicLong dropped = new AtomicLong();

    public LogEventBuffer(AgentIdentity identity, int capacity, long dedupeIntervalMs) {
        this.identity = identity;
        this.queue = new ArrayBlockingQueue<BackendLogEvent>(capacity);
        this.dedupeIntervalMs = dedupeIntervalMs;
    }

    public boolean offer(String logLevel, String message, String className, String methodName, int lineNumber,
                      long threadId, String throwable) {
        long now = System.currentTimeMillis();
        String severity = "ERROR".equalsIgnoreCase(logLevel) || "SEVERE".equalsIgnoreCase(logLevel)
                ? "EMERGENCY" : "IMPORTANT";
        BackendLogEvent event = new BackendLogEvent(now, severity, logLevel, message, className, methodName,
                lineNumber, threadId, throwable);
        String key = String.valueOf(logLevel) + '|' + className + '|' + methodName + '|' + message + '|' + throwable;
        synchronized (lastSeen) {
            Long previous = lastSeen.get(key);
            if (previous != null && now - previous < dedupeIntervalMs) return true;
            if (!queue.offer(event)) {
                dropped.incrementAndGet();
                return false;
            }
            lastSeen.put(key, now);
            if (lastSeen.size() > 4096) {
                long cutoff = now - Math.max(dedupeIntervalMs, TimeUnit.MINUTES.toMillis(1));
                for (Map.Entry<String, Long> entry : lastSeen.entrySet())
                    if (entry.getValue() < cutoff) lastSeen.remove(entry.getKey(), entry.getValue());
            }
        }
        return true;
    }

    public List<BackendLogEvent> drain(int limit) {
        List<BackendLogEvent> result = new ArrayList<BackendLogEvent>();
        queue.drainTo(result, limit);
        return result;
    }

    public void requeue(BackendLogEvent event) {
        if (event != null && !queue.offer(event)) dropped.incrementAndGet();
    }

    public int size() { return queue.size(); }
    public long getDroppedCount() { return dropped.get(); }
    public AgentIdentity getIdentity() { return identity; }
}
