package com.jagent.bootstrap;

public final class AttachSmokeTarget {
    private AttachSmokeTarget() { }
    public static void main(String[] args) throws Exception {
        System.out.println("ATTACH_TARGET_READY " + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        while (true) Thread.sleep(1000L);
    }
}
