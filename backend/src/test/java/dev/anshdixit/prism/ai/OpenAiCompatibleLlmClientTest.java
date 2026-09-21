package dev.anshdixit.prism.ai;

import com.sun.net.httpserver.HttpServer;
import dev.anshdixit.prism.ai.openai.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the adapter against an in-process stub of the OpenAI chat-completions protocol. */
class OpenAiCompatibleLlmClientTest {

    final JsonMapper mapper = JsonMapper.builder().build();
    HttpServer server;
    final List<Map<String, String>> receivedHeaders = new ArrayList<>();
    final List<JsonNode> receivedBodies = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile int failFirstWithStatus = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            receivedHeaders.add(Map.of("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")), "ua", String.valueOf(ex.getRequestHeaders().getFirst("User-Agent"))));
            receivedBodies.add(mapper.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (n == 1 && failFirstWithStatus != 0) {
                ex.sendResponseHeaders(failFirstWithStatus, -1);
                ex.close();
                return;
            }
            String content = "{\\\"decision\\\":\\\"DECLINE\\\",\\\"summary\\\":\\\"ok\\\"}";
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleLlmClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new OpenAiCompatibleLlmClient(HttpClient.newHttpClient(), mapper, base, "test-model", "sk-test", "low", Duration.ofSeconds(5));
    }

    private static LlmRequest request() {
        return new LlmRequest(LlmTask.ADVERSE_ACTION_NOTICE, "SYSTEM PROMPT", "USER PROMPT", Map.of(), 300);
    }

    @Test
    void sendsProtocolShapedRequestAndParsesCompletion() {
        LlmResponse r = client().complete(request());
        assertThat(r.text()).isEqualTo("{\"decision\":\"DECLINE\",\"summary\":\"ok\"}");
        assertThat(r.inputTokens()).isEqualTo(42);
        assertThat(r.outputTokens()).isEqualTo(7);
        assertThat(r.model()).isEqualTo("test-model");
        assertThat(r.provider()).isEqualTo("ollama"); // 127.0.0.1 is treated as a local server

        assertThat(receivedHeaders.get(0)).containsEntry("auth", "Bearer sk-test");
        assertThat(receivedHeaders.get(0).get("ua")).startsWith("prism-underwriting/");
        JsonNode body = receivedBodies.get(0);
        assertThat(body.path("model").asString()).isEqualTo("test-model");
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("low");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(300);
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo("SYSTEM PROMPT");
        assertThat(body.path("messages").get(1).path("role").asString()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asString()).isEqualTo("USER PROMPT");
    }

    @Test
    void retriesOnceAfterRateLimit() {
        failFirstWithStatus = 429;
        LlmResponse r = client().complete(request());
        assertThat(r.text()).contains("DECLINE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failsFastOnServerErrorSoTheGuardrailCanFallBack() {
        failFirstWithStatus = 500;
        assertThatThrownBy(() -> client().complete(request())).isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 500");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void providerNameFollowsTheHost() {
        assertThat(OpenAiCompatibleLlmClient.providerNameFor("https://api.groq.com/openai/v1")).isEqualTo("groq");
        assertThat(OpenAiCompatibleLlmClient.providerNameFor("http://localhost:11434/v1")).isEqualTo("ollama");
        assertThat(OpenAiCompatibleLlmClient.providerNameFor("https://llm.internal.bank/v1")).isEqualTo("openai-compatible");
    }
}
