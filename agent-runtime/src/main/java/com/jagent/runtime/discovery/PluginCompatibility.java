package com.jagent.runtime.discovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Declared runtime, framework and private library compatibility for one plugin. */
public final class PluginCompatibility {
    private final String javaVersionRange;
    private final String coreApiRange;
    private final Map<String, String> frameworkRanges;
    private final Map<String, String> libraryRanges;

    public PluginCompatibility(String javaVersionRange, String coreApiRange,
                               Map<String, String> frameworkRanges, Map<String, String> libraryRanges) {
        this.javaVersionRange = javaVersionRange;
        this.coreApiRange = coreApiRange;
        this.frameworkRanges = Collections.unmodifiableMap(new LinkedHashMap<String, String>(frameworkRanges));
        this.libraryRanges = Collections.unmodifiableMap(new LinkedHashMap<String, String>(libraryRanges));
    }
    public String getJavaVersionRange() { return javaVersionRange; }
    public String getCoreApiRange() { return coreApiRange; }
    public Map<String, String> getFrameworkRanges() { return frameworkRanges; }
    public Map<String, String> getLibraryRanges() { return libraryRanges; }
}
