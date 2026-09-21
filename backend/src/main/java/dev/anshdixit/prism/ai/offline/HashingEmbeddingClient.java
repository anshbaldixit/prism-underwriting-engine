package dev.anshdixit.prism.ai.offline;

import dev.anshdixit.prism.ai.EmbeddingClient;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Dependency-free embedding for offline runs: character n-gram (3..5) feature hashing into a 1024-dim space,
 * signed to reduce collision bias, L2-normalised. It captures lexical similarity ("KROGER #1182" ~ "KROGER"),
 * not meaning - which is exactly enough for transaction categorisation and policy search in a demo, and it
 * lets the whole system run with zero credentials. Swap for Titan v2 with one env var for real semantics.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final int MIN_N = 3;
    private static final int MAX_N = 5;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        String s = " " + normalise(text) + " ";
        for (int n = MIN_N; n <= MAX_N; n++) {
            for (int i = 0; i + n <= s.length(); i++) {
                int h = fnv1a(s.substring(i, i + n));
                int idx = Math.floorMod(h, DIMENSIONS);
                v[idx] += (h & 0x8000_0000) == 0 ? 1f : -1f;
            }
        }
        // Whole-token hits carry extra weight so exact merchant names dominate substrings.
        for (String tok : s.trim().split("\\s+")) {
            if (tok.length() >= 3) {
                int h = fnv1a("#" + tok);
                v[Math.floorMod(h, DIMENSIONS)] += (h & 0x8000_0000) == 0 ? 2f : -2f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }

    @Override
    public String providerName() {
        return "offline-hashing";
    }

    static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9&.\\-\\s]", " ").replaceAll("\\d{3,}", "#").replaceAll("\\s+", " ").trim();
    }

    private static int fnv1a(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        return h;
    }
}
