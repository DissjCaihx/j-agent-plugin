package com.jagent.runtime.extension;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExtensionRegistryImplTest {
    interface Greeting { String value(); }

    @Test
    public void registersLooksUpAndUnregistersByPlugin() {
        ExtensionRegistryImpl registry = new ExtensionRegistryImpl();
        Greeting greeting = new Greeting() { public String value() { return "hello"; } };
        registry.register("plugin-a", Greeting.class, greeting);
        assertSame(greeting, registry.get(Greeting.class));
        assertEquals(1, registry.getAll(Greeting.class).size());
        registry.unregisterAll("plugin-a");
        assertNull(registry.get(Greeting.class));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateOwnerRegistration() {
        ExtensionRegistryImpl registry = new ExtensionRegistryImpl();
        Greeting greeting = new Greeting() { public String value() { return "hello"; } };
        registry.register("plugin-a", Greeting.class, greeting);
        registry.register("plugin-a", Greeting.class, greeting);
    }
}
