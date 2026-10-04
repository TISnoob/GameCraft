package io.github.tis199.gamecraft.chess;

import io.github.tis199.gamecraft.api.EngineConfig;
import io.github.tis199.gamecraft.engine.ai.UciEngine;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Lazily starts the configured Stockfish 16-compatible UCI executable. */
public final class StockfishManager implements AutoCloseable {
    private final Map<String, UciEngine> engines = new ConcurrentHashMap<>();
    private final Logger logger;

    public StockfishManager(Logger logger) {
        this.logger = logger;
    }

    public synchronized UciEngine getOrCreateEngine(String id, EngineConfig config) throws IOException {
        UciEngine existing = engines.get(id);
        if (existing != null) return existing;
        UciEngine engine = new UciEngine(id, config);
        try {
            engine.start();
            engines.put(id, engine);
            logger.info("Started verified Stockfish UCI engine for " + id + ".");
            return engine;
        } catch (IOException exception) {
            engine.close();
            throw exception;
        }
    }

    public void closeAll() {
        engines.values().forEach(UciEngine::close);
        engines.clear();
    }

    @Override public void close() { closeAll(); }
}
