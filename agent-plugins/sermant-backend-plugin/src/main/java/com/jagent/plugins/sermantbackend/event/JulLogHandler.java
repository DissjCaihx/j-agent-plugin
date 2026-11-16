package com.jagent.plugins.sermantbackend.event;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Level;

public final class JulLogHandler extends Handler {
    private final LogEventBuffer buffer;
    private final boolean warnings;
    private final boolean errors;

    public JulLogHandler(LogEventBuffer buffer, boolean warnings, boolean errors) {
        this.buffer = buffer; this.warnings = warnings; this.errors = errors;
        setLevel(Level.WARNING);
    }

    @Override public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        String source = record.getSourceClassName();
        if (source != null && source.startsWith("com.jagent.plugins.sermantbackend.")) return;
        boolean error = record.getLevel().intValue() >= Level.SEVERE.intValue();
        if (error ? !errors : !warnings) return;
        String message;
        try { message = getFormatter() == null ? record.getMessage() : getFormatter().formatMessage(record); }
        catch (Exception ignored) { message = record.getMessage(); }
        Throwable thrown = record.getThrown();
        buffer.offer(error ? "ERROR" : record.getLevel().getName(), message, source,
                record.getSourceMethodName(), 0, record.getThreadID(), thrown == null ? null : thrown.toString());
    }

    @Override public void flush() { }
    @Override public void close() { }
}
