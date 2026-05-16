# ArrowLoader

Spigot plugin that downloads the Arrow AntiCheat JAR at runtime via a Pastebin URL and loads it in-memory through a custom ClassLoader (`MemoryJarLoader`).

## Build

```sh
mvn clean package
```

Output: `target/ArrowLoader.jar` (bStats shaded via `maven-shade-plugin`, relocated to `me.arrow.libs.bstats`).

## Key architecture

- **`Core`** (`me.arrow.Core`) extends `JavaPlugin`. On enable: fetches download URL from `https://pastebin.com/raw/EVQABwGt` → downloads JAR → instantiates `me.arrow.Arrow` via reflection and calls `onEnable()`/`onDisable()`.
- **`MemoryJarLoader`** — custom `ClassLoader` that reads a JAR from a byte array and loads classes on demand (`ConcurrentHashMap`, entries consumed on first load).
- JAR is **cached to disk** (`anticheat.jar` + `download_url.txt`). On startup: if cached URL matches Pastebin's response, cache is used directly (no download). If download fails, cache is loaded as fallback.
- Update checker polls Pastebin every 30 min via a daemon thread. When a new URL is detected, sets `updateAvailable` flag, broadcasts to `arrowloader.admin`, and auto-reloads if enabled.
- Download has retry logic: 2 retries with 1s/3s backoff. The full fetch+download cycle also retries on startup with exponential backoff (1s/2s/4s).
- bStats custom charts track cache source, server type, load time, and reload count.
- **Zero-gap reload** (`/arrowloader reload`): new JAR is downloaded and the new instance is enabled *before* the old one is shut down.
- Folia detection via classpath check for `io.papermc.paper.threadedregions.RegionizedServer`. Folia-specific scheduler calls use reflection (no compile-time dependency).

## Commands

| Command | Description |
|---|---|
| `/arrowloader status` | Version, server type, download URL, load time/source, cache, update status, auto-reload state |
| `/arrowloader reload` | Async download → swap instances without gap |
| `/arrowloader autoreload <on\|off>` | Toggle automatic reload on update detection |
| `/arrow`, `/stuck` | Delegated to the loaded anticheat (declared in `plugin.yml`, not handled locally) |

## Events (for other plugins)

- **`ArrowLoadEvent`** — fired after anticheat instance is created and enabled. Contains `getArrowInstance()`.
- **`ArrowUnloadEvent`** — fired before anticheat instance is shut down.

Both extend `org.bukkit.event.Event`. Listen via `getServer().getPluginManager().registerEvents()`.

## Notable constraints

- Java 16 source/target, UTF-8 encoding.
- `load: POSTWORLD` in `plugin.yml`.
- Permission `arrowloader.admin` (default: op) required for all commands and update broadcasts.
- `BSTATS_PLUGIN_ID = 31291` in `Core.java`. Set to `0` to disable metrics.
- Download URL comes from a Pastebin raw page. First non-empty, non-`#` line is used. JAR must be ≤64 MB and start with ZIP magic bytes (`PK\3\4`).
- No test infrastructure exists (no `src/test`).
