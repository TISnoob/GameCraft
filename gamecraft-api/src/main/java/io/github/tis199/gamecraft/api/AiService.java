package io.github.tis199.gamecraft.api;

import java.util.concurrent.CompletionStage;

/** Optional server-configured AI service. Requests are always asynchronous. */
public interface AiService {
    boolean isEnabled();

    CompletionStage<AiResponse> generate(AiRequest request);
}
