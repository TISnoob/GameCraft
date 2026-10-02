package io.github.tis199.gamecraft.paper.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameModuleContext;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.ModuleConfiguration;
import io.github.tis199.gamecraft.paper.platform.PaperGameCraftServices;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Startup-only loader for independently versioned modules published by GameCraft. */
public final class GameModuleManager implements AutoCloseable {
    public static final int API_VERSION = 1;
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");

    private final JavaPlugin plugin;
    private final PaperGameCraftServices services;
    private final Executor executor;
    private final HttpClient httpClient;
    private final Path moduleDirectory;
    private final List<LoadedModule> loaded = new ArrayList<>();

    public GameModuleManager(JavaPlugin plugin, PaperGameCraftServices services, Executor executor) {
        this.plugin = plugin;
        this.services = services;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.moduleDirectory = plugin.getDataFolder().toPath().resolve("modules");
    }

    public void loadCachedModules() {
        try {
            Files.createDirectories(moduleDirectory);
            promotePendingModules();
        } catch (IOException exception) {
            plugin.getLogger().severe("Cannot access GameCraft module cache: " + exception.getMessage());
            return;
        }

        for (String id : enabledIds()) {
            if (!VALID_ID.matcher(id).matches()) {
                plugin.getLogger().warning("Ignoring invalid module id in config.yml: " + id);
                continue;
            }
            Path jar = moduleDirectory.resolve(id + ".jar");
            if (!Files.isRegularFile(jar)) {
                plugin.getLogger().info("Game module '" + id + "' is enabled but not downloaded yet.");
                continue;
            }
            loadModule(id, jar);
        }
        if (enabledIds().isEmpty()) {
            plugin.getLogger().info("No game modules are enabled.");
        }
    }

    public void checkRegistryAsync() {
        List<String> enabled = enabledIds();
        if (enabled.isEmpty()) {
            return;
        }
        String registry = plugin.getConfig().getString("modules.manifest-url", "").trim();
        if (registry.isEmpty()) {
            plugin.getLogger().warning("Modules are enabled, but modules.manifest-url is empty.");
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
            try {
                JsonObject manifest = fetchManifest(registry);
                int schemaVersion = manifest.get("schemaVersion").getAsInt();
                int apiVersion = manifest.get("apiVersion").getAsInt();
                if (schemaVersion != 1 || apiVersion > API_VERSION) {
                    throw new IOException("Unsupported module registry/API version " + schemaVersion + "/" + apiVersion);
                }
                JsonArray entries = manifest.getAsJsonArray("modules");
                for (String id : enabled) {
                    ManifestEntry entry = findEntry(entries, id);
                    if (entry == null) {
                        plugin.getLogger().warning("Module '" + id + "' is not listed in the configured GameCraft registry.");
                        continue;
                    }
                    verifyAndStage(entry);
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not check/download GameCraft modules: " + exception.getMessage());
            }
        });
    }

    public List<GameModule> loadedModules() {
        return loaded.stream().map(LoadedModule::module).toList();
    }

    public List<String> enabledIds() {
        return List.copyOf(plugin.getConfig().getStringList("modules.enabled-games"));
    }

    private JsonObject fetchManifest(String registry) throws IOException, InterruptedException {
        URI uri = trustedUri(registry);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("registry returned HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private ManifestEntry findEntry(JsonArray entries, String id) throws IOException {
        for (int i = 0; i < entries.size(); i++) {
            JsonObject value = entries.get(i).getAsJsonObject();
            if (!id.equals(value.get("id").getAsString())) {
                continue;
            }
            String version = value.get("version").getAsString();
            String url = value.get("url").getAsString();
            String sha256 = value.get("sha256").getAsString();
            if (version.isBlank() || !SHA_256.matcher(sha256).matches()) {
                throw new IOException("invalid version or SHA-256 for module " + id);
            }
            return new ManifestEntry(id, version, trustedUri(url), sha256.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private void verifyAndStage(ManifestEntry entry) throws IOException, InterruptedException, NoSuchAlgorithmException {
        Path current = moduleDirectory.resolve(entry.id + ".jar");
        Path pending = moduleDirectory.resolve(entry.id + ".pending.jar");
        if (Files.isRegularFile(current) && sha256(current).equals(entry.sha256)) {
            return;
        }

        Path temporary = moduleDirectory.resolve(entry.id + ".download.part");
        HttpRequest request = HttpRequest.newBuilder(entry.url).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporary);
            throw new IOException("download for " + entry.id + " returned HTTP " + response.statusCode());
        }
        if (!sha256(temporary).equals(entry.sha256)) {
            Files.deleteIfExists(temporary);
            throw new IOException("SHA-256 mismatch for downloaded module " + entry.id);
        }

        Path destination = Files.isRegularFile(current) ? pending : current;
        moveAtomically(temporary, destination);
        plugin.getLogger().info("Downloaded verified module '" + entry.id + "' version " + entry.version
                + (destination.equals(pending) ? "; restart to activate the update." : "; restart to load it."));
    }

    private void loadModule(String id, Path jar) {
        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    GameModule.class.getClassLoader());
            ServiceLoader<GameModule> serviceLoader = ServiceLoader.load(GameModule.class, classLoader);
            int count = 0;
            for (GameModule module : serviceLoader) {
                GameModuleDescriptor descriptor = module.descriptor();
                if (!descriptor.id().equals(id)) {
                    throw new IllegalStateException("module id does not match enabled id " + id);
                }
                if (descriptor.apiVersion() > API_VERSION) {
                    throw new IllegalStateException("module requires GameCraft API " + descriptor.apiVersion());
                }
                Path data = plugin.getDataFolder().toPath().resolve("games").resolve(id);
                Files.createDirectories(data);
                Path configFile = plugin.getDataFolder().toPath().resolve("games").resolve(id + ".yml");
                ensureModuleConfig(module, configFile);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile.toFile());
                module.onLoad(new GameModuleContext(data, new YamlModuleConfiguration(yaml), services));
                module.onEnable();
                loaded.add(new LoadedModule(module, classLoader));
                count++;
                plugin.getLogger().info("Loaded game module " + descriptor.displayName() + " " + descriptor.version());
            }
            if (count == 0) {
                classLoader.close();
                throw new IllegalStateException("jar contains no GameModule service provider");
            }
        } catch (ServiceConfigurationError | Exception exception) {
            plugin.getLogger().warning("Failed to load game module '" + id + "': " + exception.getMessage());
        }
    }

    private void ensureModuleConfig(GameModule module, Path destination) throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        try (InputStream defaults = module.getClass().getClassLoader().getResourceAsStream("gamecraft-defaults.yml")) {
            if (defaults == null) {
                Files.writeString(destination, "# Settings for GameCraft game module '" + module.descriptor().id() + "'.\n");
            } else {
                Files.copy(defaults, destination);
            }
        }
    }

    private void promotePendingModules() throws IOException {
        try (var paths = Files.list(moduleDirectory)) {
            for (Path pending : paths.filter(path -> path.getFileName().toString().endsWith(".pending.jar")).toList()) {
                String name = pending.getFileName().toString();
                Path current = moduleDirectory.resolve(name.substring(0, name.length() - ".pending.jar".length()) + ".jar");
                moveAtomically(pending, current);
            }
        }
    }

    private static URI trustedUri(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean githubHost = host != null && (host.equalsIgnoreCase("github.com")
                    || host.equalsIgnoreCase("raw.githubusercontent.com")
                    || host.endsWith(".githubusercontent.com"));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !githubHost || uri.getUserInfo() != null) {
                throw new IOException("only HTTPS GitHub release/registry URLs are accepted");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid module URL", exception);
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedModule module = loaded.get(i);
            try {
                module.module.onDisable();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Error disabling module " + module.module.descriptor().id()
                        + ": " + exception.getMessage());
            }
            try {
                module.classLoader.close();
            } catch (IOException ignored) {
            }
        }
        loaded.clear();
    }

    private record ManifestEntry(String id, String version, URI url, String sha256) {
    }

    private record LoadedModule(GameModule module, URLClassLoader classLoader) {
    }

    private record YamlModuleConfiguration(YamlConfiguration yaml) implements ModuleConfiguration {
        @Override
        public java.util.Optional<String> getString(String path) {
            return yaml.isString(path) ? java.util.Optional.ofNullable(yaml.getString(path)) : java.util.Optional.empty();
        }

        @Override
        public boolean getBoolean(String path, boolean defaultValue) {
            return yaml.getBoolean(path, defaultValue);
        }

        @Override
        public int getInt(String path, int defaultValue) {
            return yaml.getInt(path, defaultValue);
        }

        @Override
        public List<String> getStringList(String path) {
            return List.copyOf(yaml.getStringList(path));
        }
    }
}
