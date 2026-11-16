package com.jagent.plugins.sermantbackend.lifecycle;

import com.jagent.api.PluginLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class SpringBootLifecycleTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "com/jagent/api/lifecycle/ApplicationContextLifecycle";
    private final PluginLogger logger;
    public SpringBootLifecycleTransformer(PluginLogger logger) { this.logger = logger; }

    @Override public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!isTarget(className)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] hooked = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"start".equals(name) || !"()V".equals(descriptor)) return delegate;
                    hooked[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != RETURN) return;
                            visitVarInsn(ALOAD, 0);
                            String fieldDescriptor = className.contains("reactive/")
                                    ? "Lorg/springframework/boot/web/reactive/context/ReactiveWebServerApplicationContext;"
                                    : "Lorg/springframework/boot/web/servlet/context/ServletWebServerApplicationContext;";
                            visitFieldInsn(GETFIELD, className, "applicationContext", fieldDescriptor);
                            visitMethodInsn(INVOKESTATIC, BRIDGE, "contextStarted", "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
            }, 0);
            if (!hooked[0]) {
                logger.warn("Spring Boot context-name hook found no start() method in " + className);
                return null;
            }
            logger.info("Enabled Spring Boot application-name hook for " + className.replace('/', '.'));
            return writer.toByteArray();
        } catch (Throwable error) {
            logger.error("Could not instrument Spring Boot lifecycle class " + className, error);
            return null;
        }
    }

    private static boolean isTarget(String name) {
        return "org/springframework/boot/web/servlet/context/WebServerStartStopLifecycle".equals(name)
                || "org/springframework/boot/web/reactive/context/WebServerManager".equals(name);
    }
}
