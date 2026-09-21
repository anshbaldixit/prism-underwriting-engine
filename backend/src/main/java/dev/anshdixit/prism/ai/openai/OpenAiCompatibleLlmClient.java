package dev.anshdixit.prism.ai.openai;

import dev.anshdixit.prism.ai.LlmClient;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapter for any endpoint that speaks the OpenAI chat-completions protocol: Groq, a local Ollama or vLLM,
 * or a model server inside a bank's VPC. Which one is purely configuration - base URL, model id and the name
 * of the environment variable holding the key. Every task asks for a JSON object, so the request uses the
 * protocol's JSON response mode; the output validator remains the authority on whether the JSON is acceptable.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final String USER_AGENT = "prism-underwriting/0.1 (Java HttpClient)";

    private final HttpClient http;
    private final JsonMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String reasoningEffort;
    private final String providerName;
    private final Duration timeout;

    public OpenAiCompatibleLlmClient(HttpClient http, JsonMapper mapper, String baseUrl, String model, String apiKey,
                                     String reasoningEffort, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
        this.timeout = timeout;
        this.providerName = providerNameFor(this.baseUrl);
    }

    /** Short, stable label for audit rows and UI badges: "groq", "ollama", or the generic protocol name. */
    public static String providerNameFor(String baseUrl) {
        String host = URI.create(baseUrl).getHost();
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.contains("groq")) {
            return "groq";
        }
        if (h.equals("localhost") || h.equals("127.0.0.1")) {
            return "ollama";
        }
        return "openai-compatible";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long t0 = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("max_tokens", request.maxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> resp = send(req.build(), true);
        JsonNode json = mapper.readTree(resp.body());
        JsonNode choice = json.path("choices").path(0).path("message");
        String text = choice.path("content").asString("");
        if (text.isBlank()) {
            throw new IllegalStateException("Empty completion from " + providerName + " (" + model + "): finish_reason="
                    + json.path("choices").path(0).path("finish_reason").asString("?"));
        }
        JsonNode usage = json.path("usage");
        Integer in = usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null;
        Integer out = usage.has("completion_tokens") ? usage.path("completion_tokens").asInt() : null;
        return new LlmResponse(text, providerName, model, in, out, (System.nanoTime() - t0) / 1_000_000);
    }

    /** One retry after a short pause on 429 (free tiers are rate-limited per minute); anything else fails fast. */
    private HttpResponse<String> send(HttpRequest request, boolean retryOnRateLimit) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429 && retryOnRateLimit) {
                Thread.sleep(2000);
                return send(request, false);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("LLM endpoint " + providerName + " returned HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            return resp;
        } catch (IOException e) {
            throw new IllegalStateException("LLM endpoint " + providerName + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + providerName, e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public String modelName() {
        return model;
    }
}
