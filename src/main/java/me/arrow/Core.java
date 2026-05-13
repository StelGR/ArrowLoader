package me.arrow;

import lombok.Getter;
import lombok.Setter;
import me.arrow.utility.MemoryJarLoader;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class Core extends JavaPlugin {

    private static final String PASTEBIN_RAW_URL = "https://pastebin.com/raw/EVQABwGt";

    /*
     * Replace 0 with your bStats plugin/service id after registering ArrowLoader on bStats.
     * Leave it as 0 if you want metrics disabled during local testing.
     */
    private static final int BSTATS_PLUGIN_ID = 31291;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;

    @Getter
    @Setter
    private static Core plugin;

    @Getter
    @Setter
    private Object arrowInstance;

    @Getter
    @Setter
    private Class<?> arrowClass;

    private MemoryJarLoader memoryJarLoader;
    private Metrics metrics;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        Core.setPlugin(this);

        try {
            startMetrics();

            String coreDownloadUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);
            byte[] jarBytes = downloadJar(coreDownloadUrl);

            memoryJarLoader = new MemoryJarLoader(jarBytes, getClassLoader());

            File arrowFolder = getDataFolder();
            if (!arrowFolder.exists() && !arrowFolder.mkdirs()) {
                throw new IOException("Failed to create plugin data folder.");
            }

            arrowClass = memoryJarLoader.loadClass("me.arrow.Arrow");
            arrowInstance = constructArrow(arrowClass, arrowFolder);

            invokeArrowEnable(arrowClass, arrowInstance);

            long endTime = System.currentTimeMillis();
            getLogger().info("Arrow Anticheat loaded successfully in " + (endTime - startTime) + "ms.");
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            getLogger().severe("Failed to initialize ArrowLoader: " + root.getClass().getSimpleName() + ": " + root.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();

        shutdownMetrics();
        shutdownArrow();
        closeMemoryLoader();

        arrowInstance = null;
        arrowClass = null;
        Core.setPlugin(null);

        long endTime = System.currentTimeMillis();
        getLogger().info("ArrowLoader shutdown in " + (endTime - startTime) + "ms.");
    }

    private void startMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            getLogger().warning("bStats metrics are disabled. Replace BSTATS_PLUGIN_ID in Core.java with your bStats plugin id.");
            return;
        }

        try {
            metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            getLogger().info("bStats metrics enabled.");
        } catch (Throwable throwable) {
            getLogger().warning("Failed to start bStats metrics: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            metrics = null;
        }
    }

    private void shutdownMetrics() {
        if (metrics == null) {
            return;
        }

        try {
            metrics.shutdown();
        } catch (Throwable ignored) {
        } finally {
            metrics = null;
        }
    }

    private Object constructArrow(Class<?> clazz, File folder) throws Exception {
        try {
            Constructor<?> constructor = clazz.getConstructor(JavaPlugin.class, File.class);
            constructor.setAccessible(true);
            return constructor.newInstance(this, folder);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Constructor<?> constructor = clazz.getConstructor(JavaPlugin.class, File.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(this, folder, 0);
        } catch (NoSuchMethodException ignored) {
        }

        throw new NoSuchMethodException("me.arrow.Arrow must have constructor (JavaPlugin, File) or (JavaPlugin, File, int).");
    }

    private void invokeArrowEnable(Class<?> clazz, Object instance) throws Exception {
        try {
            Method method = clazz.getMethod("onEnable");
            method.setAccessible(true);
            method.invoke(instance);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Method method = clazz.getMethod("onEnable", int.class);
            method.setAccessible(true);
            method.invoke(instance, 0);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        throw new NoSuchMethodException("me.arrow.Arrow must have onEnable() or onEnable(int).");
    }

    private void shutdownArrow() {
        if (arrowInstance == null || arrowClass == null) {
            return;
        }

        try {
            try {
                Method onDisable = arrowClass.getMethod("onDisable");
                onDisable.setAccessible(true);
                onDisable.invoke(arrowInstance);
                getLogger().info("Arrow Anticheat unloaded successfully.");
                return;
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method shutdown = arrowClass.getMethod("shutdown");
                shutdown.setAccessible(true);
                shutdown.invoke(arrowInstance);
                getLogger().info("Arrow Anticheat shutdown successfully.");
            } catch (NoSuchMethodException ignored) {
                getLogger().warning("Arrow Anticheat has no onDisable() or shutdown() method.");
            }
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            getLogger().severe("ArrowLoader had an error while shutting down Arrow: " + root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }

    private void closeMemoryLoader() {
        if (memoryJarLoader == null) {
            return;
        }

        try {
            memoryJarLoader.close();
        } catch (Throwable ignored) {
        } finally {
            memoryJarLoader = null;
        }
    }

    private String fetchDownloadUrl(String pastebinRawUrl) throws IOException {
        URL pastebinUrl = validateHttpUrl(pastebinRawUrl, "Pastebin URL");
        URLConnection connection = pastebinUrl.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ArrowLoader/" + getDescription().getVersion());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                String cleaned = cleanLine(line);

                if (cleaned.isEmpty() || cleaned.startsWith("#")) {
                    continue;
                }

                validateHttpUrl(cleaned, "Arrow core download URL");
                return cleaned;
            }
        }

        throw new IOException("Pastebin response did not contain a download URL.");
    }

    private byte[] downloadJar(String urlString) throws IOException {
        URL url = validateHttpUrl(urlString, "Arrow core download URL");
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ArrowLoader/" + getDescription().getVersion());

        int contentLength = connection.getContentLength();
        if (contentLength > MAX_JAR_BYTES) {
            throw new IOException("Downloaded JAR is too large.");
        }

        try (InputStream inputStream = connection.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.max(contentLength, BUFFER_SIZE))) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            int total = 0;

            while ((read = inputStream.read(buffer)) != -1) {
                total += read;

                if (total > MAX_JAR_BYTES) {
                    throw new IOException("Downloaded JAR exceeded maximum allowed size.");
                }

                outputStream.write(buffer, 0, read);
            }

            byte[] bytes = outputStream.toByteArray();

            if (!looksLikeJar(bytes)) {
                throw new IOException("Downloaded file does not look like a valid JAR.");
            }

            return bytes;
        }
    }

    private URL validateHttpUrl(String value, String name) throws IOException {
        if (value == null) {
            throw new IOException(name + " is null.");
        }

        String cleaned = cleanLine(value);
        if (cleaned.isEmpty()) {
            throw new IOException(name + " is empty.");
        }

        URL url = new URL(cleaned);
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);

        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IOException(name + " must use http or https.");
        }

        return url;
    }

    private String cleanLine(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .trim();
    }

    private boolean looksLikeJar(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && bytes[2] == 3
                && bytes[3] == 4;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }

        return current == null ? throwable : current;
    }

    public boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public String getServerImplementationName() {
        return isFolia() ? "Folia" : Bukkit.getName();
    }
}