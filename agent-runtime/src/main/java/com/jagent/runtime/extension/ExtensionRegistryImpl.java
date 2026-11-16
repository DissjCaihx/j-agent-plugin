package com.jagent.runtime.extension;

import com.jagent.api.extension.ExtensionRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtensionRegistryImpl implements ExtensionRegistry {
    private final Map<Class<?>, List<Entry>> entries = new LinkedHashMap<Class<?>, List<Entry>>();

    public synchronized <T> void register(String pluginId, Class<T> type, T extension) {
        if (pluginId == null || type == null || extension == null)
            throw new IllegalArgumentException("Registration values must not be null");
        if (!type.isInstance(extension))
            throw new IllegalArgumentException("Extension does not implement " + type.getName());
        List<Entry> values = entries.get(type);
        if (values == null) {
            values = new ArrayList<Entry>();
            entries.put(type, values);
        }
        for (Entry entry : values)
            if (entry.pluginId.equals(pluginId))
                throw new IllegalStateException("Plugin already registered this extension type: " + pluginId);
        values.add(new Entry(pluginId, extension));
    }

    public synchronized <T> T get(Class<T> type) {
        List<T> all = getAll(type);
        return all.isEmpty() ? null : all.get(0);
    }

    public synchronized <T> List<T> getAll(Class<T> type) {
        List<Entry> values = entries.get(type);
        if (values == null) return Collections.emptyList();
        List<T> result = new ArrayList<T>();
        for (Entry entry : values) result.add(type.cast(entry.extension));
        return Collections.unmodifiableList(result);
    }

    public synchronized void unregisterAll(String pluginId) {
        Iterator<Map.Entry<Class<?>, List<Entry>>> types = entries.entrySet().iterator();
        while (types.hasNext()) {
            List<Entry> values = types.next().getValue();
            for (Iterator<Entry> it = values.iterator(); it.hasNext(); )
                if (it.next().pluginId.equals(pluginId)) it.remove();
            if (values.isEmpty()) types.remove();
        }
    }

    private static final class Entry {
        final String pluginId;
        final Object extension;

        Entry(String pluginId, Object extension) {
            this.pluginId = pluginId;
            this.extension = extension;
        }
    }
}
