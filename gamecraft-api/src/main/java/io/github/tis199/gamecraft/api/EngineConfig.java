package io.github.tis199.gamecraft.api;

import java.util.Map;

public record EngineConfig(
        String path,
        String downloadUrl,
        String downloadSha256,
        Map<String, String> options,
        int maxThreads,
        int maxMemoryMb,
        int timeoutMs
) {
    public EngineConfig {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Engine path must not be blank");
        }
        downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
        downloadSha256 = downloadSha256 == null ? "" : downloadSha256.trim();
        options = Map.copyOf(options);
        if (maxThreads < 1 || maxThreads > 256 || maxMemoryMb < 16 || maxMemoryMb > 65_536 || timeoutMs < 100) {
            throw new IllegalArgumentException("Invalid engine resource limits");
        }
        if (!downloadUrl.isEmpty() && !downloadSha256.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("An engine download requires a SHA-256 checksum");
        }
    }
}
