package io.github.tis199.gamecraft.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Namespaced asynchronous key/value storage for module-owned persistent state. */
public interface StorageService {
    CompletionStage<Optional<byte[]>> get(String namespace, String key);

    CompletionStage<Void> put(String namespace, String key, byte[] value);

    CompletionStage<Void> remove(String namespace, String key);
}
