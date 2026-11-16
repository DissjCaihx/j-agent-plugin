package com.jagent.plugins.sermantbackend.event;

import com.jagent.api.PluginLogger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Attaches a tiny proxy appender when the host application uses Logback, without linking its classes. */
public final class LogbackCapture implements AutoCloseable {
    private final LogEventBuffer buffer;
    private final boolean warnings;
    private final boolean errors;
    private final PluginLogger logger;
    private Object rootLogger;
    private Object appender;
    private Method detachMethod;

    public LogbackCapture(LogEventBuffer buffer, boolean warnings, boolean errors, PluginLogger logger) {
        this.buffer = buffer; this.warnings = warnings; this.errors = errors; this.logger = logger;
    }

    public void start() {
        try {
            ClassLoader hostLoader = Thread.currentThread().getContextClassLoader();
            if (hostLoader == null) hostLoader = ClassLoader.getSystemClassLoader();
            Class<?> loggerFactory = Class.forName("org.slf4j.LoggerFactory", false, hostLoader);
            Object factory = loggerFactory.getMethod("getILoggerFactory").invoke(null);
            if (!"ch.qos.logback.classic.LoggerContext".equals(factory.getClass().getName())) return;
            Class<?> appenderType = Class.forName("ch.qos.logback.core.Appender", false, hostLoader);
            rootLogger = factory.getClass().getMethod("getLogger", String.class).invoke(factory, "ROOT");
            InvocationHandler handler = new AppenderHandler(factory, buffer, warnings, errors);
            appender = Proxy.newProxyInstance(hostLoader, new Class<?>[]{appenderType}, handler);
            rootLogger.getClass().getMethod("addAppender", appenderType).invoke(rootLogger, appender);
            for (Method method : rootLogger.getClass().getMethods()) {
                if ("detachAppender".equals(method.getName()) && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(appenderType)) {
                    detachMethod = method; break;
                }
            }
            logger.info("Attached Sermant event capture to host Logback root logger");
        } catch (ClassNotFoundException ignored) {
            // Logback is optional; JUL capture remains active.
        } catch (Throwable error) {
            logger.warn("Could not attach to host Logback logger: " + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
    }

    @Override public void close() {
        if (rootLogger == null || appender == null) return;
        try {
            if (detachMethod != null) detachMethod.invoke(rootLogger, appender);
            else rootLogger.getClass().getMethod("detachAppender", String.class).invoke(rootLogger, "j-agent-sermant-event-appender");
        } catch (Throwable error) {
            logger.warn("Could not detach Sermant Logback event appender: " + error.getClass().getSimpleName());
        }
        rootLogger = null; appender = null;
    }

    private static final class AppenderHandler implements InvocationHandler {
        private final Object context;
        private final LogEventBuffer buffer;
        private final boolean warnings;
        private final boolean errors;
        AppenderHandler(Object context, LogEventBuffer buffer, boolean warnings, boolean errors) {
            this.context = context; this.buffer = buffer; this.warnings = warnings; this.errors = errors;
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getName".equals(name)) return "j-agent-sermant-event-appender";
            if ("getContext".equals(name)) return context;
            if ("isStarted".equals(name)) return Boolean.TRUE;
            if ("getCopyOfAttachedFiltersList".equals(name)) return java.util.Collections.emptyList();
            if ("getFilterChainDecision".equals(name)) return null;
            if ("toString".equals(name)) return "JAgentSermantBackendAppender";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if (("doAppend".equals(name) || "append".equals(name)) && args != null && args.length > 0)
                capture(args[0]);
            if (method.getReturnType() == boolean.class) return Boolean.FALSE;
            if (method.getReturnType() == int.class) return Integer.valueOf(0);
            return null;
        }
        private void capture(Object event) {
            try {
                String level = String.valueOf(event.getClass().getMethod("getLevel").invoke(event));
                boolean error = "ERROR".equalsIgnoreCase(level);
                if (error ? !errors : !warnings || !("WARN".equalsIgnoreCase(level))) return;
                String message = string(event, "getFormattedMessage");
                String loggerName = string(event, "getLoggerName");
                if (loggerName != null && loggerName.startsWith("com.jagent.plugins.sermantbackend.")) return;
                String thread = string(event, "getThreadName");
                Object[] caller = (Object[]) event.getClass().getMethod("getCallerData").invoke(event);
                String className = null, methodName = null;
                int line = 0;
                if (caller != null && caller.length > 0) {
                    StackTraceElement first = (StackTraceElement) caller[0];
                    className = first.getClassName(); methodName = first.getMethodName(); line = first.getLineNumber();
                }
                String throwable = null;
                try {
                    Object proxy = event.getClass().getMethod("getThrowableProxy").invoke(event);
                    if (proxy != null) throwable = String.valueOf(proxy.getClass().getMethod("getMessage").invoke(proxy));
                } catch (Exception ignored) { }
                buffer.offer(error ? "ERROR" : "WARN", message, className == null ? loggerName : className,
                        methodName, line, thread == null ? 0 : thread.hashCode(), throwable);
            } catch (Throwable ignored) { }
        }
        private static String string(Object object, String method) throws Exception {
            Object value = object.getClass().getMethod(method).invoke(object);
            return value == null ? null : String.valueOf(value);
        }
    }
}
