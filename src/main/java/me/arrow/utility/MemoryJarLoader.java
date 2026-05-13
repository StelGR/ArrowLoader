package me.arrow.utility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public final class MemoryJarLoader extends ClassLoader implements AutoCloseable {

    private final Map<String, byte[]> classes = new HashMap<>();
    private final Map<String, byte[]> resources = new HashMap<>();

    public MemoryJarLoader(byte[] jarBytes, ClassLoader parent) throws IOException {
        super(parent);
        loadJarBytes(jarBytes);
    }

    private void loadJarBytes(byte[] jarBytes) throws IOException {
        if (jarBytes == null || jarBytes.length == 0) {
            throw new IOException("JAR bytes are empty.");
        }

        try (JarInputStream jarInputStream = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;

            while ((entry = jarInputStream.getNextJarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;

                while ((read = jarInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }

                byte[] data = outputStream.toByteArray();
                String name = entry.getName();

                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    classes.put(className, data);
                } else {
                    resources.put(name, data);
                }
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.remove(name);

        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }

        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] data = resources.get(name);

        if (data != null) {
            return new ByteArrayInputStream(data);
        }

        return super.getResourceAsStream(name);
    }

    @Override
    public void close() {
        classes.clear();
        resources.clear();
    }
}