package com.jagent.plugins.springbootnacos.lifecycle;

import com.jagent.api.PluginLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Adds framework-neutral lifecycle callbacks after Spring Boot has bound or stopped its web server.
 */
public final class SpringBootLifecycleTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "com/jagent/api/lifecycle/ApplicationContextLifecycle";
    private final PluginLogger logger;

    public SpringBootLifecycleTransformer(PluginLogger logger) {
        this.logger = logger;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!isTarget(className)) return null;
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] hooked = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String callback = callback(className, name, descriptor);
                    if (callback == null) return delegate;
                    hooked[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(int opcode) {
                            if (opcode != RETURN) return;
                            visitVarInsn(ALOAD, 0);
                            String owner = className;
                            String field = "applicationContext";
                            String contextType = owner.contains("reactive/")
                                    ? "Lorg/springframework/boot/web/reactive/context/ReactiveWebServerApplicationContext;"
                                    : "Lorg/springframework/boot/web/servlet/context/ServletWebServerApplicationContext;";
                            visitFieldInsn(GETFIELD, owner, field, contextType);
                            visitMethodInsn(INVOKESTATIC, BRIDGE, callback, "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
            }, 0);
            if (!hooked[0]) {
                logger.warn("Spring Boot lifecycle class matched but no supported start/stop methods were found: " + className);
                return null;
            }
            logger.info("Enabled Spring Boot web lifecycle hook for " + className.replace('/', '.'));
            return writer.toByteArray();
        } catch (Throwable error) {
            logger.error("Could not instrument Spring Boot lifecycle class " + className, error);
            return null;
        }
    }

    private static boolean isTarget(String className) {
        return "org/springframework/boot/web/servlet/context/WebServerStartStopLifecycle".equals(className)
                || "org/springframework/boot/web/reactive/context/WebServerManager".equals(className);
    }

    private static String callback(String className, String method, String descriptor) {
        if (!"()V".equals(descriptor)) return null;
        if (className.endsWith("WebServerManager")) {
            if ("start".equals(method)) return "contextStarted";
            if ("stop".equals(method)) return "contextStopped";
        } else {
            if ("start".equals(method)) return "contextStarted";
            if ("stop".equals(method)) return "contextStopped";
        }
        return null;
    }
}
