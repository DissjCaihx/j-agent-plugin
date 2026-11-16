package com.jagent.plugins.sermantbackend.event;

public final class BackendLogEvent {
    private final long time;
    private final String level;
    private final String logLevel;
    private final String message;
    private final String className;
    private final String methodName;
    private final int lineNumber;
    private final long threadId;
    private final String throwable;

    public BackendLogEvent(long time, String level, String logLevel, String message, String className,
                           String methodName, int lineNumber, long threadId, String throwable) {
        this.time = time; this.level = level; this.logLevel = logLevel; this.message = clean(message);
        this.className = className; this.methodName = methodName; this.lineNumber = lineNumber;
        this.threadId = threadId; this.throwable = clean(throwable);
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)(password|token|secret)(\\s*[=:]\\s*)[^,;\\s]+", "$1$2****");
    }
    public long getTime() { return time; }
    public String getLevel() { return level; }
    public String getLogLevel() { return logLevel; }
    public String getMessage() { return message; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public int getLineNumber() { return lineNumber; }
    public long getThreadId() { return threadId; }
    public String getThrowable() { return throwable; }
}
