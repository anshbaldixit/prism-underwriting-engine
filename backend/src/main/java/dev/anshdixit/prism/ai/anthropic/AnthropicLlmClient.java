package dev.anshdixit.prism.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import dev.anshdixit.prism.ai.LlmClient;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmResponse;

/**
 * Direct Anthropic API adapter (the "equivalent LLM service" path). Same prompts, same validator, same audit
 * trail as Bedrock; only the transport differs. The key is read from ANTHROPIC_API_KEY by the SDK.
 */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmClient(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long t0 = System.nanoTime();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) request.maxTokens())
                .system(request.systemPrompt())
                .addUserMessage(request.userPrompt())
                .build();
        Message response = client.messages().create(params);
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", String::concat);
        return new LlmResponse(text, providerName(), model,
                (int) response.usage().inputTokens(), (int) response.usage().outputTokens(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public String providerName() {
        return "anthropic";
    }
}
