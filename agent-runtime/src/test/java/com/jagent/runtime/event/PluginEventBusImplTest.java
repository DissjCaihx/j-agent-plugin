package com.jagent.runtime.event;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PluginEventBusImplTest {
    @Test
    public void publishesToMatchingListenersAndUnsubscribesByOwner() {
        PluginEventBusImpl bus = new PluginEventBusImpl();
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe("owner-a", String.class, event -> calls.incrementAndGet());
        bus.subscribe("owner-b", Integer.class, event -> calls.addAndGet(10));
        bus.publish("message");
        assertEquals(1, calls.get());
        bus.unsubscribeAll("owner-a");
        bus.publish("again");
        assertEquals(1, calls.get());
    }

    @Test
    public void listenerFailureDoesNotPreventOtherListeners() {
        PluginEventBusImpl bus = new PluginEventBusImpl();
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe("broken", String.class, event -> { throw new RuntimeException("expected"); });
        bus.subscribe("healthy", String.class, event -> calls.incrementAndGet());
        bus.publish("event");
        assertEquals(1, calls.get());
    }
}
