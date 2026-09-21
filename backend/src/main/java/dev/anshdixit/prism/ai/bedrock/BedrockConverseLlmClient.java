package dev.anshdixit.prism.ai.bedrock;

import dev.anshdixit.prism.ai.LlmClient;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmResponse;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Amazon Bedrock adapter using the model-agnostic Converse API. Credentials come from the default AWS chain
 * (env vars, shared profile, or an IAM task role in ECS) - the application never holds a key. Model choice is
 * configuration: any Claude / Nova / Llama model enabled in the account can be dropped in by ID.
 */
public class BedrockConverseLlmClient implements LlmClient {

    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockConverseLlmClient(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long t0 = System.nanoTime();
        ConverseRequest req = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(request.systemPrompt()))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(request.userPrompt()))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(request.maxTokens())
                        .temperature(0.2f)
                        .build())
                .build();
        ConverseResponse resp = client.converse(req);
        String text = resp.output().message().content().stream()
                .filter(c -> c.text() != null)
                .map(ContentBlock::text)
                .reduce("", String::concat);
        Integer in = resp.usage() == null ? null : resp.usage().inputTokens();
        Integer out = resp.usage() == null ? null : resp.usage().outputTokens();
        return new LlmResponse(text, providerName(), modelId, in, out, (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public String providerName() {
        return "bedrock";
    }
}
