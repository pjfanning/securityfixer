package com.tersesystems.securityfixer.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.Listener;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.dynamic.ClassFileLocator.CLASS_FILE_EXTENSION;
import static net.bytebuddy.matcher.ElementMatchers.*;
import net.bytebuddy.matcher.StringMatcher;

public class SecurityFixerAgent {

    private static final List<Class<?>> BOOTSTRAP_CLASSES = Arrays.asList(
            MySystemInterceptor.class
    );

    private SecurityFixerAgent() {
    }

    public static void premain(String arg, Instrumentation instrumentation) {
        injectBootstrapClasses(instrumentation);
        new AgentBuilder.Default()
                .with(RedefinitionStrategy.RETRANSFORMATION)
                .with(InitializationStrategy.NoOp.INSTANCE)
                .with(TypeStrategy.Default.REDEFINE)
                .ignore(new AgentBuilder.RawMatcher.ForElementMatchers(nameStartsWith("net.bytebuddy.").or(isSynthetic()), any(), any()))
                .with(new Listener.Filtering(
                        new StringMatcher("java.lang.System", StringMatcher.Mode.EQUALS_FULLY),
                        Listener.StreamWriting.toSystemOut()))
                .type(named("java.lang.System"))
                .transform((builder, type, classLoader, module) ->
                        builder.visit(Advice.to(MySystemInterceptor.class).on(named("setSecurityManager")))
                )
                .installOn(instrumentation);
    }

    private static void injectBootstrapClasses(Instrumentation instrumentation) {
        try {
            File jarFile = File.createTempFile(SecurityFixerAgent.class.getSimpleName(), ".jar");
            jarFile.deleteOnExit();
            try (JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)))) {
                for (Class<?> bootstrapClass : BOOTSTRAP_CLASSES) {
                    String klassPath = classFileFullname(bootstrapClass);
                    jarOutputStream.putNextEntry(new JarEntry(klassPath));
                    jarOutputStream.write(readFully(bootstrapClass.getClassLoader().getResourceAsStream(klassPath)));
                }
            }
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write jar file to disk", exception);
        }
    }

    private static String classFileFullname(Class<?> bootstrapClass) {
        return bootstrapClass.getName().replace('.', '/') + CLASS_FILE_EXTENSION;
    }

    private static byte[] readFully(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }
    }

}