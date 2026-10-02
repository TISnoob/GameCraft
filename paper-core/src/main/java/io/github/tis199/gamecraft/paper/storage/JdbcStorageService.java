package io.github.tis199.gamecraft.paper.storage;

import io.github.tis199.gamecraft.api.StorageService;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQLite for standalone servers and MariaDB for networks, behind one module API. */
public final class JdbcStorageService implements StorageService, AutoCloseable {
    private final JavaPlugin plugin;
    private final Executor executor;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public JdbcStorageService(JavaPlugin plugin, Executor executor) {
        this.plugin = plugin;
        this.executor = executor;
        String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase();
        if (type.equals("sqlite")) {
            String file = plugin.getConfig().getString("storage.sqlite-file", "gamecraft.db");
            Path dbPath = plugin.getDataFolder().toPath().resolve(file).normalize();
            if (!dbPath.startsWith(plugin.getDataFolder().toPath().normalize())) {
                throw new IllegalArgumentException("storage.sqlite-file must stay inside the plugin folder");
            }
            this.jdbcUrl = "jdbc:sqlite:" + dbPath;
            this.username = "";
            this.password = "";
        } else if (type.equals("mariadb")) {
            String host = plugin.getConfig().getString("storage.mariadb.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.mariadb.port", 3306);
            String database = plugin.getConfig().getString("storage.mariadb.database", "gamecraft");
            boolean ssl = plugin.getConfig().getBoolean("storage.mariadb.use-ssl", false);
            this.jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?sslMode="
                    + (ssl ? "verify-full" : "disable");
            this.username = plugin.getConfig().getString("storage.mariadb.username", "gamecraft");
            this.password = plugin.getConfig().getString("storage.mariadb.password", "");
        } else {
            throw new IllegalArgumentException("storage.type must be sqlite or mariadb");
        }
    }

    public void initialize() throws SQLException {
        try (Connection connection = connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gc_module_data ("
                    + "namespace VARCHAR(64) NOT NULL, data_key VARCHAR(191) NOT NULL, "
                    + "data_value BLOB NOT NULL, PRIMARY KEY(namespace, data_key))");
        }
    }

    @Override
    public CompletionStage<Optional<byte[]>> get(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT data_value FROM gc_module_data WHERE namespace=? AND data_key=?")) {
                statement.setString(1, checkedNamespace(namespace));
                statement.setString(2, checkedKey(key));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("GameCraft storage read failed", exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Void> put(String namespace, String key, byte[] value) {
        byte[] safeValue = value.clone();
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO gc_module_data(namespace,data_key,data_value) VALUES(?,?,?) "
                                 + "ON CONFLICT(namespace,data_key) DO UPDATE SET data_value=excluded.data_value")) {
                statement.setString(1, checkedNamespace(namespace));
                statement.setString(2, checkedKey(key));
                statement.setBytes(3, safeValue);
                statement.executeUpdate();
            } catch (SQLException sqliteSyntaxOrStorageFailure) {
                putMariaDb(namespace, key, safeValue, sqliteSyntaxOrStorageFailure);
            }
        }, executor);
    }

    private void putMariaDb(String namespace, String key, byte[] value, SQLException original) {
        if (!jdbcUrl.startsWith("jdbc:mariadb:")) {
            throw new IllegalStateException("GameCraft storage write failed", original);
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gc_module_data(namespace,data_key,data_value) VALUES(?,?,?) "
                             + "ON DUPLICATE KEY UPDATE data_value=VALUES(data_value)")) {
            statement.setString(1, checkedNamespace(namespace));
            statement.setString(2, checkedKey(key));
            statement.setBytes(3, value);
            statement.executeUpdate();
        } catch (SQLException exception) {
            exception.addSuppressed(original);
            throw new IllegalStateException("GameCraft storage write failed", exception);
        }
    }

    @Override
    public CompletionStage<Void> remove(String namespace, String key) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM gc_module_data WHERE namespace=? AND data_key=?")) {
                statement.setString(1, checkedNamespace(namespace));
                statement.setString(2, checkedKey(key));
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("GameCraft storage delete failed", exception);
            }
        }, executor);
    }

    private Connection connect() throws SQLException {
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            return DriverManager.getConnection(jdbcUrl);
        }
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String checkedNamespace(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Namespace must be lowercase alphanumeric/hyphen and at most 64 chars");
        }
        return value;
    }

    private static String checkedKey(String value) {
        if (value == null || value.isBlank() || value.length() > 191) {
            throw new IllegalArgumentException("Storage key must contain 1-191 characters");
        }
        return value;
    }

    @Override
    public void close() {
        // Connections are scoped to each asynchronous operation.
    }
}
