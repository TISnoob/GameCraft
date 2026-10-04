package io.github.tis199.gamecraft.paper.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosts the bundled universal Java pack and sends it directly through the standard server resource-pack prompt. */
public final class ResourcePackService implements Listener, AutoCloseable {
    private static final String RESOURCE = "packs/gamecraft-java-universal.zip";
    private static final String ROUTE = "/gamecraft.zip";

    private final JavaPlugin plugin;
    private final byte[] pack;
    private final byte[] sha1;
    private final String sha1Hex;
    private final HttpServer http;
    private final ExecutorService httpExecutor;
    private final String publicUrl;
    private final boolean required;
    private final String prompt;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            pack = null;
            sha1 = null;
            sha1Hex = "";
            http = null;
            httpExecutor = null;
            publicUrl = "";
            required = false;
            prompt = "";
            plugin.getLogger().info("Automatic Java resource-pack delivery is disabled.");
            return;
        }

        byte[] loaded = readPack();
        if (loaded == null) {
            pack = null;
            sha1 = null;
            sha1Hex = "";
            http = null;
            httpExecutor = null;
            publicUrl = "";
            required = false;
            prompt = "";
            plugin.getLogger().severe("The bundled GameCraft resource pack is missing; rebuild GameCraft.jar with assembleDistribution.");
            return;
        }
        pack = loaded;
        try {
            sha1 = MessageDigest.getInstance("SHA-1").digest(pack);
            sha1Hex = HexFormat.of().formatHex(sha1);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
        publicUrl = plugin.getConfig().getString("resource-pack.public-url", "").trim();
        required = plugin.getConfig().getBoolean("resource-pack.required", true);
        prompt = plugin.getConfig().getString("resource-pack.prompt",
                "Download the GameCraft boards and game pieces?");

        HttpServer server = null;
        ExecutorService executor = null;
        if (plugin.getConfig().getBoolean("resource-pack.host.enabled", true)) {
            try {
                String bind = plugin.getConfig().getString("resource-pack.host.bind-address", "0.0.0.0");
                int port = Math.max(1, Math.min(65535, plugin.getConfig().getInt("resource-pack.host.port", 8765)));
                server = HttpServer.create(new InetSocketAddress(bind, port), 16);
                server.createContext(ROUTE, this::serve);
                executor = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "GameCraft-pack-http");
                    thread.setDaemon(true);
                    return thread;
                });
                server.setExecutor(executor);
                server.start();
                plugin.getLogger().info("GameCraft Java pack hosted on port " + port + " at " + ROUTE
                        + " (SHA-1 " + sha1Hex + ").");
            } catch (IOException exception) {
                if (server != null) server.stop(0);
                if (executor != null) executor.shutdownNow();
                server = null;
                executor = null;
                plugin.getLogger().warning("Could not host the GameCraft pack: " + exception.getMessage());
            }
        }
        http = server;
        httpExecutor = executor;
        if (publicUrl.isBlank()) {
            plugin.getLogger().warning("Set resource-pack.public-url to the public URL ending in " + ROUTE
                    + "; players will then receive this pack automatically on join.");
        } else if (!validUrl(publicUrl)) {
            plugin.getLogger().warning("resource-pack.public-url must be an HTTP(S) URL without credentials; pack delivery is disabled.");
        } else {
            plugin.getLogger().info("Automatic Java pack prompt is ready (required=" + required + ").");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public String sha1Hex() { return sha1Hex; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (pack == null || !validUrl(publicUrl)) return;
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) return;
            player.setResourcePack(publicUrl, sha1, Component.text(prompt), required);
        }, null, 20L);
    }

    private byte[] readPack() {
        try (InputStream input = plugin.getResource(RESOURCE)) {
            if (input == null) return null;
            byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024) {
                plugin.getLogger().severe("Bundled GameCraft resource pack exceeds the 64 MiB safety limit.");
                return null;
            }
            return bytes;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not read bundled GameCraft pack: " + exception.getMessage());
            return null;
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.getResponseHeaders().set("ETag", "\"" + sha1Hex + "\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(pack.length));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, pack.length);
            try (var output = exchange.getResponseBody()) { output.write(pack); }
        }
        exchange.close();
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        if (http != null) http.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }
}
