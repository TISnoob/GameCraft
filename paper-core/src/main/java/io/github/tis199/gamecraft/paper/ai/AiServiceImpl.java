package io.github.tis199.gamecraft.paper.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tis199.gamecraft.api.AiRequest;
import io.github.tis199.gamecraft.api.AiResponse;
import io.github.tis199.gamecraft.api.AiService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Generic asynchronous text-generation adapters for configured AI providers. */
public final class AiServiceImpl implements AiService {
    private static final Map<String, String> OPENAI_COMPATIBLE_ENDPOINTS = Map.of(
            "openai", "https://api.openai.com/v1/chat/completions",
            "groq", "https://api.groq.com/openai/v1/chat/completions",
            "xai", "https://api.x.ai/v1/chat/completions",
            "openrouter", "https://openrouter.ai/api/v1/chat/completions");

    private final JavaPlugin plugin;
    private final Executor executor;
    private final HttpClient httpClient;

    public AiServiceImpl(JavaPlugin plugin, Executor executor) {
        this.plugin = plugin;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("ai.enabled", false) && apiKey() != null;
    }

    @Override
    public CompletionStage<AiResponse> generate(AiRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            if (!plugin.getConfig().getBoolean("ai.enabled", false)) {
                throw new IllegalStateException("AI is disabled in GameCraft config.yml");
            }
            String provider = plugin.getConfig().getString("ai.provider", "gemini").toLowerCase();
            String key = apiKey(provider);
            if (key == null) {
                throw new IllegalStateException("No API key configured for AI provider " + provider);
            }
            String model = plugin.getConfig().getString("ai.providers." + provider + ".model", "");
            if (model.isBlank()) {
                throw new IllegalStateException("No model configured for AI provider " + provider);
            }
            int timeoutSeconds = Math.max(5, plugin.getConfig().getInt("ai.timeout-seconds", 45));
            try {
                HttpRequest httpRequest = switch (provider) {
                    case "gemini" -> geminiRequest(key, model, request, timeoutSeconds);
                    case "anthropic" -> anthropicRequest(key, model, request, timeoutSeconds);
                    case "openai", "groq", "xai", "openrouter" -> openAiRequest(
                            provider, key, model, request, timeoutSeconds);
                    default -> throw new IllegalArgumentException("Unsupported AI provider: " + provider);
                };
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("AI provider " + provider + " returned HTTP "
                            + response.statusCode() + ": " + trim(response.body(), 500));
                }
                return new AiResponse(provider, model, parseText(provider, response.body()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI request was interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("AI request failed: " + exception.getMessage(), exception);
            }
        }, executor);
    }

    private HttpRequest geminiRequest(String key, String model, AiRequest request, int timeout) {
        JsonObject body = new JsonObject();
        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", request.systemPrompt());
        systemParts.add(systemPart);
        systemInstruction.add("parts", systemParts);
        body.add("systemInstruction", systemInstruction);

        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", request.prompt());
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        body.add("contents", contents);

        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", request.temperature());
        generation.addProperty("maxOutputTokens", request.maxOutputTokens());
        body.add("generationConfig", generation);

        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + encodedKey);
        return jsonRequest(uri, body, timeout).build();
    }

    private HttpRequest anthropicRequest(String key, String model, AiRequest request, int timeout) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", request.maxOutputTokens());
        body.addProperty("temperature", request.temperature());
        body.addProperty("system", request.systemPrompt());
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", request.prompt());
        messages.add(message);
        body.add("messages", messages);
        return jsonRequest(URI.create("https://api.anthropic.com/v1/messages"), body, timeout)
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .build();
    }

    private HttpRequest openAiRequest(String provider, String key, String model,
                                     AiRequest request, int timeout) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", request.temperature());
        body.addProperty("max_tokens", request.maxOutputTokens());
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", request.systemPrompt()));
        messages.add(chatMessage("user", request.prompt()));
        body.add("messages", messages);
        return jsonRequest(URI.create(OPENAI_COMPATIBLE_ENDPOINTS.get(provider)), body, timeout)
                .header("Authorization", "Bearer " + key)
                .build();
    }

    private HttpRequest.Builder jsonRequest(URI uri, JsonObject body, int timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    }

    private static JsonObject chatMessage(String role, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", text);
        return message;
    }

    private String apiKey() {
        String provider = plugin.getConfig().getString("ai.provider", "gemini").toLowerCase();
        return apiKey(provider);
    }

    private String apiKey(String provider) {
        String path = "ai.providers." + provider;
        String configured = plugin.getConfig().getString(path + ".api-key", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String envName = plugin.getConfig().getString(path + ".api-key-env", "").trim();
        if (envName.isEmpty()) {
            return null;
        }
        String fromEnvironment = System.getenv(envName);
        return fromEnvironment == null || fromEnvironment.isBlank() ? null : fromEnvironment;
    }

    private static String parseText(String provider, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return switch (provider) {
            case "gemini" -> root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString();
            case "anthropic" -> root.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString();
            default -> root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        };
    }

    private static String trim(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }
}
