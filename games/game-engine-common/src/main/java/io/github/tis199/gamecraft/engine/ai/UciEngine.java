package io.github.tis199.gamecraft.engine.ai;

import io.github.tis199.gamecraft.api.EngineConfig;
import io.github.tis199.gamecraft.api.LocalEngine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** UCI process adapter with bounded startup/search waits and optional verified binary download. */
public final class UciEngine implements LocalEngine, AutoCloseable {
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String id;
    private final EngineConfig config;
    private final ExecutorService engineExecutor;
    private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile String engineName = "";

    public UciEngine(String id, EngineConfig config) {
        this.id = id;
        this.config = config;
        this.engineExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "GameCraft-engine-" + id);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() throws IOException {
        if (closed.get()) throw new IOException("Engine is closed");
        if (process != null && process.isAlive()) return;
        output.clear();
        engineName = "";
        Path binary = Path.of(config.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(binary)) {
            if (config.downloadUrl().isBlank()) {
                throw new IOException("Engine executable not found at " + binary + " and no download URL is configured");
            }
            download(binary);
        }
        if (!Files.isRegularFile(binary)) throw new IOException("Engine download did not produce a file");
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                binary.toFile().setExecutable(true, true);
            }
        } catch (SecurityException ignored) {
            // ProcessBuilder will return a clear error if execution is not permitted.
        }

        ProcessBuilder builder = new ProcessBuilder(binary.toString());
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread stdout = new Thread(() -> pump(process.getInputStream()), "GameCraft-engine-out-" + id);
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(() -> drain(process.getErrorStream()), "GameCraft-engine-err-" + id);
        stderr.setDaemon(true);
        stderr.start();

        send("uci");
        await("uciok");
        if (id.equalsIgnoreCase("stockfish") && !engineName.toLowerCase(java.util.Locale.ROOT).contains("stockfish")) {
            closeProcess();
            throw new IOException("Chess requires Stockfish; the configured UCI engine identified itself as '"
                    + (engineName.isBlank() ? "unknown" : engineName) + "'");
        }
        for (Map.Entry<String, String> option : config.options().entrySet()) {
            if (option.getKey().equalsIgnoreCase("download-sha256")) continue;
            validateLimit(option.getKey(), option.getValue());
            send("setoption name " + safeToken(option.getKey()) + " value " + safeToken(option.getValue()));
        }
        send("isready");
        await("readyok");
    }

    @Override public String id() { return id; }

    @Override
    public CompletionStage<String> compute(String position, String options) {
        if (position == null || position.isBlank() || position.contains("\n") || position.contains("\r")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid engine position"));
        }
        if (options == null || !options.matches("go (depth [1-9][0-9]?|movetime [1-9][0-9]{0,6}|nodes [1-9][0-9]{0,8})")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid UCI search limit"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureRunning();
                send("position fen " + position);
                send(options);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
                while (!closed.get() && System.nanoTime() < deadline) {
                    String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (line == null) continue;
                    if (line.startsWith("bestmove ")) {
                        String[] fields = line.split("\\s+");
                        return fields.length > 1 && !fields[1].equals("(none)") && !fields[1].equals("0000")
                                ? fields[1] : "";
                    }
                }
                stopTimedOutSearch();
                throw new IOException("Engine search timed out after " + config.timeoutMs() + "ms");
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, engineExecutor);
    }

    private void ensureRunning() throws IOException {
        if (closed.get()) throw new IOException("Engine is closed");
        if (process == null || !process.isAlive()) start();
    }

    private synchronized void send(String command) throws IOException {
        if (command.contains("\n") || command.contains("\r")) throw new IOException("Invalid UCI command");
        if (writer == null) throw new IOException("Engine is not started");
        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    private void validateLimit(String name, String value) throws IOException {
        if (!name.equalsIgnoreCase("Threads") && !name.equalsIgnoreCase("Hash")) return;
        int configured;
        try {
            configured = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Engine option " + name + " must be an integer", exception);
        }
        if (name.equalsIgnoreCase("Threads") && (configured < 1 || configured > config.maxThreads())) {
            throw new IOException("Engine Threads option exceeds its configured limit");
        }
        if (name.equalsIgnoreCase("Hash") && (configured < 16 || configured > config.maxMemoryMb())) {
            throw new IOException("Engine Hash option exceeds its configured memory limit");
        }
    }

    private void stopTimedOutSearch() {
        try {
            send("stop");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
            while (System.nanoTime() < deadline) {
                String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line != null && line.startsWith("bestmove ")) return;
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        Process running = process;
        if (running != null) running.destroyForcibly();
        process = null;
        writer = null;
    }

    private void await(String expected) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
        while (System.nanoTime() < deadline) {
            String line;
            try {
                line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for UCI engine", exception);
            }
            if (expected.equals(line)) return;
            if (line != null && line.startsWith("id name ")) engineName = line.substring("id name ".length()).trim();
            if (process == null || !process.isAlive()) throw new IOException("Engine exited during " + expected + " handshake");
        }
        throw new IOException("Timed out waiting for UCI engine " + expected);
    }

    private void download(Path destination) throws IOException {
        URI uri;
        try { uri = URI.create(config.downloadUrl()); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid engine download URL", exception); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Engine download URL must use HTTPS and must not include user credentials");
        }
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + ".download.part");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Engine download interrupted", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Engine download returned HTTP " + response.statusCode());
        }
        long count = 0;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (InputStream input = response.body(); var output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_DOWNLOAD_BYTES) throw new IOException("Engine download exceeds 256 MiB");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        if (!checksum.equalsIgnoreCase(config.downloadSha256())) {
            Files.deleteIfExists(partial);
            throw new IOException("Engine SHA-256 checksum mismatch");
        }
        try {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeToken(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) throw new IOException("Invalid UCI option value");
        return value;
    }

    private void pump(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.offer(line);
        } catch (IOException ignored) { }
    }

    private static void drain(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) { /* drain engine diagnostics */ }
        } catch (IOException ignored) { }
    }

    private void closeProcess() {
        Process running = process;
        if (running != null) running.destroyForcibly();
        process = null;
        writer = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { if (writer != null) send("quit"); }
        catch (IOException ignored) { }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(750, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        engineExecutor.shutdownNow();
    }
}
