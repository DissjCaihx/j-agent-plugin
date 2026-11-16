package com.jagent.api;

public interface PluginLogger {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);
}
