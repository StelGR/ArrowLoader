package me.arrow;

import me.arrow.utility.MemoryJarLoader;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.ChatColor;
import java.util.Objects;
import me.arrow.command.UpdateCommand;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class Core extends JavaPlugin {

    public static Core instance;

    private static final String PASTEBIN_RAW_URL = "https://pastebin.com/raw/EVQABwGt";
    private static final String GIST_RAW_URL = "https://gist.githubusercontent.com/StelGR/49765843ecc471e338bd35b2648e544f/raw/gistfile1.txt";

    private static final List<String> DOWNLOAD_URL_SOURCES = Arrays.asList(
        PASTEBIN_RAW_URL,
        GIST_RAW_URL
    );

    /*
     * Replace 0 with your bStats plugin/service id after registering ArrowLoader on bStats.
     * Leave it as 0 if you want metrics disabled during local testing.
     */
    private static final int BSTATS_PLUGIN_ID = 31291;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;
    private static final String BUKKIT_ENTRYPOINT_CLASS = "me.arrow.backend.bukkit.ArrowBukkitPlugin";
    private static final String BUKKIT_CORE_CLASS = "me.arrow.Arrow";

    private Object arrowInstance;

    private Class<?> arrowClass;

    public Object getArrowInstance() {
        return arrowInstance;
    }

    public void setArrowInstance(Object arrowInstance) {
        this.arrowInstance = arrowInstance;
    }

    public Class<?> getArrowClass() {
        return arrowClass;
    }

    public void setArrowClass(Class<?> arrowClass) {
        this.arrowClass = arrowClass;
    }

    /**
     * Returns the Arrow core class, loading it if necessary.
     */
    public Class<?> getOrLoadArrowClass() {
        if (arrowClass != null) return arrowClass;
        // Load core without a command sender (silent)
        loadCore(null, false);
        return arrowClass;
    }

    private MemoryJarLoader memoryJarLoader;
    private Metrics metrics;

    @Override
    public void onEnable() {
        instance = this;
        // Load core without a command sender (no chat feedback)
        // Delay loading core until after server startup to avoid "Server is still loading" issues
        Bukkit.getScheduler().runTaskLater(instance, () -> loadCore(null, false), 5L);
        Objects.requireNonNull(getCommand("updatearrow")).setExecutor(new UpdateCommand(instance));
    }

    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();

        shutdownMetrics();
        shutdownArrow();
        closeMemoryLoader();

        arrowInstance = null;
        // arrowClass is retained for reload flag handling
        instance = null;

        long endTime = System.currentTimeMillis();
        getLogger().info("ArrowLoader shutdown in " + (endTime - startTime) + "ms.");
    }

    private void startMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            getLogger().warning("bStats metrics are disabled. Replace BSTATS_PLUGIN_ID in Core.java with your bStats plugin id.");
            return;
        }

        try {
            metrics = new Metrics(instance, BSTATS_PLUGIN_ID);
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

    private Object constructArrow(Class<?> bukkitEntrypoint, Class<?> clazz, File folder) throws Exception {
        try {
            Method factory = bukkitEntrypoint.getMethod("createForLoader", JavaPlugin.class, File.class);
            factory.setAccessible(true);
            Object arrow = factory.invoke(null, instance, folder);
            if (!clazz.isInstance(arrow)) {
                throw new IllegalStateException("Bukkit loader bridge returned "
                        + (arrow == null ? "null" : arrow.getClass().getName()) + " instead of " + clazz.getName());
            }
            return arrow;
        } catch (NoSuchMethodException ignored) {
            // Fall through only for an older Bukkit Arrow JAR; Fabric JARs are rejected before this point.
        }

        try {
            Constructor<?> constructor = clazz.getConstructor(JavaPlugin.class, File.class);
            constructor.setAccessible(true);
            return constructor.newInstance(instance, folder);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Constructor<?> constructor = clazz.getConstructor(JavaPlugin.class, File.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(instance, folder, 0);
        } catch (NoSuchMethodException ignored) {
        }

        throw new NoSuchMethodException("me.arrow.Arrow must have constructor (JavaPlugin, File) or (JavaPlugin, File, int).");
    }

    private void validateBukkitArtifact() throws IOException {
        if (!memoryJarLoader.hasResource("plugin.yml")) {
            throw new IOException("Downloaded Arrow JAR has no Bukkit plugin.yml.");
        }
//        if (memoryJarLoader.hasResource("fabric.mod.json")) {
//            throw new IOException("ArrowLoader accepts Bukkit Arrow JARs only; a Fabric artifact was downloaded.");
//        }

        if (!memoryJarLoader.hasClass(BUKKIT_ENTRYPOINT_CLASS)) {
            throw new IOException("Downloaded Bukkit Arrow JAR has no " + BUKKIT_ENTRYPOINT_CLASS + " entrypoint.");
        }
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

    private byte[] downloadCoreJar(CommandSender sender) throws IOException {
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < DOWNLOAD_URL_SOURCES.size(); i++) {
            String sourceUrl = DOWNLOAD_URL_SOURCES.get(i);
            String sourceLabel = (i == 0) ? "primary source" : "backup source #" + i;

            try {
                String downloadUrl = fetchDownloadUrlFromSource(sourceUrl);
                if (sender != null) {
                    sender.sendMessage(ChatColor.YELLOW + "Download URL retrieved from " + sourceLabel + ". Downloading core...");
                } else {
                    getLogger().info("Download URL retrieved from " + sourceLabel + ". Downloading core...");
                }

                byte[] jarBytes = downloadJar(downloadUrl);
                if (sender != null) {
                    sender.sendMessage(ChatColor.YELLOW + "Download complete. Loading core...");
                } else {
                    getLogger().info("Download complete. Loading core...");
                }
                return jarBytes;
            } catch (Exception e) {
                String cleanError = sanitizeUrls(e.getMessage());
                String errorMsg = "Failed to retrieve core from " + sourceLabel + ": " + cleanError;
                getLogger().warning(errorMsg + (i + 1 < DOWNLOAD_URL_SOURCES.size() ? ". Trying backup source..." : ""));
                if (sender != null) {
                    sender.sendMessage(ChatColor.RED + errorMsg + (i + 1 < DOWNLOAD_URL_SOURCES.size() ? ". Trying backup..." : ""));
                }
                errors.add(sourceLabel + " (" + cleanError + ")");
            }
        }

        throw new IOException("All download sources failed: " + String.join("; ", errors));
    }

    private String fetchDownloadUrl() throws IOException {
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < DOWNLOAD_URL_SOURCES.size(); i++) {
            String sourceUrl = DOWNLOAD_URL_SOURCES.get(i);
            String sourceLabel = (i == 0) ? "primary source" : "backup source #" + i;
            try {
                return fetchDownloadUrlFromSource(sourceUrl);
            } catch (Exception e) {
                String cleanError = sanitizeUrls(e.getMessage());
                getLogger().warning("Failed to fetch download URL from " + sourceLabel + " (" + cleanError + "). Trying next source...");
                errors.add(sourceLabel + " (" + cleanError + ")");
            }
        }

        throw new IOException("Failed to fetch download URL from all sources: " + String.join(", ", errors));
    }

    private String sanitizeUrls(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.replaceAll("https?://\\S+", "[REDACTED_URL]");
    }

    private String fetchDownloadUrl(String url) throws IOException {
        return fetchDownloadUrlFromSource(url);
    }

    private String fetchDownloadUrlFromSource(String sourceUrl) throws IOException {
        URL url = validateHttpUrl(sourceUrl, "Download URL source");
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ArrowLoader/" + getDescription().getVersion());

        if (connection instanceof HttpURLConnection) {
            HttpURLConnection httpConn = (HttpURLConnection) connection;
            httpConn.setInstanceFollowRedirects(true);
            int responseCode = httpConn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP response code " + responseCode);
            }
        }

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

        throw new IOException("Response did not contain a valid download URL.");
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

    public boolean loadCore(CommandSender sender, boolean reloading) {
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Downloading Arrow core...");
        } else {
            getLogger().info("Downloading Arrow core...");
        }
        long start = System.currentTimeMillis();
        try {
            startMetrics();
            byte[] jarBytes = downloadCoreJar(sender);
            memoryJarLoader = new MemoryJarLoader(jarBytes, this.getClass().getClassLoader());
            validateBukkitArtifact();
            Class<?> bukkitEntrypoint = memoryJarLoader.loadClass(BUKKIT_ENTRYPOINT_CLASS);
            Class<?> clazz = memoryJarLoader.loadClass(BUKKIT_CORE_CLASS);
            arrowClass = clazz;
            arrowInstance = constructArrow(bukkitEntrypoint, clazz, getDataFolder());
            if (sender != null) {
                sender.sendMessage(ChatColor.YELLOW + "Invoking Arrow onEnable...");
            }

            if (reloading) {
                try {
                    Class<?> arrowCls = getOrLoadArrowClass();
                    java.lang.reflect.Method set = arrowCls.getMethod("setReloadingTrue");
                    set.invoke(null);
                    getLogger().info("[ReloadFlag] Arrow.reloading set to true");
                } catch (Exception e) {
                    getLogger().severe("Failed to set reloading flag via reflection: " + e);
                }
            }

            invokeArrowEnable(clazz, arrowInstance);

            if (reloading) {
                // Reset the reload flag after core has loaded (120 ticks delay)
                Bukkit.getScheduler().runTaskLater(instance, () -> {
                    try {
                        Class<?> arrowCls = getOrLoadArrowClass();
                        java.lang.reflect.Method reset = arrowCls.getDeclaredMethod("resetReloading");
                        reset.setAccessible(true);
                        reset.invoke(null);
                        getLogger().info("[ReloadFlag] Arrow.reloading set to false");
                    } catch (Exception e) {
                        getLogger().severe("Failed to reset reloading flag via reflection: " + e);
                    }
                }, 120L);
            }

            long end = System.currentTimeMillis();
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "Arrow core loaded successfully in " + (end - start) + "ms.");
            } else {
                getLogger().info("Arrow core loaded successfully in " + (end - start) + "ms.");
            }
            return true;
        } catch (Exception e) {
            shutdownMetrics();
            closeMemoryLoader();
            arrowInstance = null;
            arrowClass = null;
            String cleanMsg = sanitizeUrls(e.getMessage());
            getLogger().severe("Failed to load Arrow core: " + cleanMsg);
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Failed to load Arrow core: " + cleanMsg);
            }
            return false;
        }
    }

    public void unloadCore(CommandSender sender) {
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Shutting down Arrow core...");
        } else {
            getLogger().info("Shutting down Arrow core...");
        }
        long start = System.currentTimeMillis();
        try {
            shutdownMetrics();
            shutdownArrow();
            closeMemoryLoader();
            arrowInstance = null;
            arrowClass = null;
            memoryJarLoader = null;
            long end = System.currentTimeMillis();
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "Arrow core shutdown complete in " + (end - start) + "ms.");
            } else {
                getLogger().info("Arrow core shutdown complete in " + (end - start) + "ms.");
            }
        } catch (Exception e) {
            getLogger().severe("Error during Arrow core shutdown: " + e.getMessage());
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Error during Arrow core shutdown: " + e.getMessage());
            }
        }
    }

    public boolean reloadCore(CommandSender sender) {
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Reloading Arrow core...");
        } else {
            getLogger().info("Reloading Arrow core...");
        }
        unloadCore(sender);
        boolean success = loadCore(sender, true);
        if (success) {
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "Arrow core reload complete.");
            } else {
                getLogger().info("Arrow core reload complete.");
            }
        } else {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Arrow core reload failed.");
            } else {
                getLogger().severe("Arrow core reload failed.");
            }
        }
        return success;
    }
}
