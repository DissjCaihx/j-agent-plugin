package com.jagent.bootstrap;

import java.lang.reflect.Method;

public final class AttachSmokeClient {
    private AttachSmokeClient() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("pid agentJar pluginDirectory");
        Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = vmType.getMethod("attach", String.class);
        Object vm = attach.invoke(null, args[0]);
        try { vmType.getMethod("loadAgent", String.class, String.class).invoke(vm, args[1], args[2]); }
        finally { vmType.getMethod("detach").invoke(vm); }
    }
}
