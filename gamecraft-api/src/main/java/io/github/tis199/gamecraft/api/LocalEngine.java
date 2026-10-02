package io.github.tis199.gamecraft.api;

import java.util.concurrent.CompletionStage;

/** Optional module-owned local engine contract. Engine work must be asynchronous. */
public interface LocalEngine {
    String id();

    CompletionStage<String> compute(String position, String options);
}
