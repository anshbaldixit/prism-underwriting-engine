package dev.anshdixit.prism.ai;

import java.util.List;

/** Text -> dense vector. All implementations produce {@link #DIMENSIONS}-length unit vectors so the pgvector schema is provider-independent. */
public interface EmbeddingClient {

    /** Fixed to Titan Text Embeddings v2's native size; the offline hashing embedder matches it. */
    int DIMENSIONS = 1024;

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    String providerName();
}
