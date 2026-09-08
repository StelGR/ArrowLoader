package me.arrow;

import lombok.Getter;
import lombok.Setter;
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
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class Core extends JavaPlugin {

    public static Core instance;

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
    private Object arrowInstance;

    @Getter
    @Setter
    private Class<?> arrowClass;

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

    private Object constructArrow(Class<?> clazz, File folder) throws Exception {
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

    public void loadCore(CommandSender sender, boolean reloading) {
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Downloading Arrow core...");
        } else {
            getLogger().info("Downloading Arrow core...");
        }
        long start = System.currentTimeMillis();
        try {
            startMetrics();
            String url = fetchDownloadUrl(PASTEBIN_RAW_URL);
            if (sender != null) {
                sender.sendMessage(ChatColor.YELLOW + "Download URL retrieved.");
            }
            byte[] jarBytes = downloadJar(url);
            if (sender != null) {
                sender.sendMessage(ChatColor.YELLOW + "Download complete. Loading core...");
            }
            memoryJarLoader = new MemoryJarLoader(jarBytes, this.getClass().getClassLoader());
            Class<?> clazz = memoryJarLoader.loadClass("me.arrow.Arrow");
            arrowClass = clazz;
            arrowInstance = constructArrow(clazz, getDataFolder());
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
        } catch (Exception e) {
            getLogger().severe("Failed to load Arrow core: " + e.getMessage());
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Failed to load Arrow core: " + e.getMessage());
            }
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

    public void reloadCore(CommandSender sender) {
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Reloading Arrow core...");
        }
        unloadCore(sender);
        loadCore(sender, true);
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Arrow core reload complete.");
        }


    }
}