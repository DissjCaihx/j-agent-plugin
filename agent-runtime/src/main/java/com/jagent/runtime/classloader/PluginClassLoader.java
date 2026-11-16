package com.jagent.runtime.classloader;

import java.net.URL;
import java.net.URLClassLoader;

public final class PluginClassLoader extends URLClassLoader {
    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("com.jagent.api.") || name.startsWith("com.jagent.runtime.")) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
