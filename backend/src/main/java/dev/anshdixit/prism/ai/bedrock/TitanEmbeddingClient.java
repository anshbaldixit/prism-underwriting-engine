package dev.anshdixit.prism.ai.bedrock;

import dev.anshdixit.prism.ai.EmbeddingClient;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Amazon Titan Text Embeddings v2 (1024-dim, normalised) through Bedrock InvokeModel. */
public class TitanEmbeddingClient implements EmbeddingClient {

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final JsonMapper mapper;

    public TitanEmbeddingClient(BedrockRuntimeClient client, String modelId, JsonMapper mapper) {
        this.client = client;
        this.modelId = modelId;
        this.mapper = mapper;
    }

    @Override
    public float[] embed(String text) {
        String body = mapper.writeValueAsString(Map.of("inputText", text, "dimensions", DIMENSIONS, "normalize", true));
        InvokeModelResponse resp = client.invokeModel(InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(body))
                .build());
        JsonNode emb = mapper.readTree(resp.body().asUtf8String()).get("embedding");
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS && i < emb.size(); i++) {
            v[i] = (float) emb.get(i).asDouble();
        }
        return v;
    }

    @Override
    public String providerName() {
        return "bedrock-titan-v2";
    }
}
