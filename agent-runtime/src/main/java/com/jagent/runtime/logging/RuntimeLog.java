package com.jagent.runtime.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class RuntimeLog {
    private static final Logger LOGGER = Logger.getLogger("com.jagent");
    private static final ConsoleHandler HANDLER = new ConsoleHandler();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile("(?i)(password|access[_-]?token|token|secret)(\\s*[=:]\\s*)([^\\s,;&]+)");
    private static Handler fileHandler;

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);
        HANDLER.setLevel(Level.ALL);
        HANDLER.setFormatter(new Formatter());
        LOGGER.addHandler(HANDLER);
        configure(System.getProperty("jagent.log.level", "INFO"));
    }

    private RuntimeLog() {
    }

    public static synchronized void configure(String levelName) {
        if (levelName == null || levelName.trim().isEmpty()) return;
        Level level;
        try {
            level = Level.parse(levelName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("[logging] Invalid log level '" + levelName + "'; using INFO");
            level = Level.INFO;
        }
        for (Handler handler : LOGGER.getHandlers()) handler.setLevel(level);
    }

    /** Creates a private rolling log under the Agent directory, separate from application logs. */
    public static synchronized void configureFile(Path agentDirectory) {
        if (fileHandler != null) return;
        String configuredDirectory = System.getProperty("jagent.log.dir");
        Path logDirectory = configuredDirectory == null || configuredDirectory.trim().isEmpty()
                ? (agentDirectory == null ? Paths.get(System.getProperty("user.dir", ".")) : agentDirectory).resolve("logs")
                : Paths.get(configuredDirectory.trim()).toAbsolutePath().normalize();
        Path activeDirectory = logDirectory;
        try {
            fileHandler = createFileHandler(logDirectory);
        } catch (IOException | SecurityException primaryError) {
            Path fallback = Paths.get(System.getProperty("user.home", "."), ".j-agent-plugin", "logs");
            try {
                fileHandler = createFileHandler(fallback);
                activeDirectory = fallback;
                LOGGER.warning("[logging] Cannot write Agent logs to " + logDirectory + "; using " + fallback + ": " + primaryError);
            } catch (IOException | SecurityException fallbackError) {
                LOGGER.log(Level.WARNING, "[logging] Agent file logging unavailable; continuing with console output", fallbackError);
                return;
            }
        }
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new Formatter());
        LOGGER.addHandler(fileHandler);
        LOGGER.info("[logging] Agent log file enabled at " + activeDirectory.toAbsolutePath().normalize()
                + "/j-agent-plugin.0.log (10 MiB per file, 5 files)");
    }

    private static Handler createFileHandler(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new FileHandler(directory.resolve("j-agent-plugin.%g.log").toString(), 10 * 1024 * 1024, 5, true);
    }

    public static void debug(String component, String message) {
        LOGGER.fine(prefix(component) + message);
    }

    public static void info(String component, String message) {
        LOGGER.info(prefix(component) + message);
    }

    public static void warn(String component, String message) {
        LOGGER.warning(prefix(component) + message);
    }

    public static void error(String component, String message, Throwable error) {
        LOGGER.log(Level.SEVERE, prefix(component) + message, error);
    }

    private static String prefix(String component) {
        return "[" + component + "] ";
    }

    private static final class Formatter extends java.util.logging.Formatter {
        public String format(LogRecord record) {
            StringBuilder out = new StringBuilder();
            out.append(TIMESTAMP.format(ZonedDateTime.now())).append(" ")
                    .append(record.getLevel().getName()).append(" [")
                    .append(Thread.currentThread().getName()).append("] ")
                    .append(redact(formatMessage(record))).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter stack = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stack));
                out.append(redact(stack.toString()));
            }
            return out.toString();
        }
    }

    private static String redact(String value) {
        return SENSITIVE_VALUE.matcher(value).replaceAll("$1$2****");
    }
}
