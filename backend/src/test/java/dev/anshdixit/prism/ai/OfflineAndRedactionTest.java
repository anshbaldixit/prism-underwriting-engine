package dev.anshdixit.prism.ai;

import dev.anshdixit.prism.ai.offline.HashingEmbeddingClient;
import dev.anshdixit.prism.ai.offline.OfflineTemplateLlmClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineAndRedactionTest {

    final JsonMapper mapper = JsonMapper.builder().build();
    final PiiRedactor redactor = new PiiRedactor();

    @Test
    void redactsIdentifiersEmailsPhonesAndCards() {
        String in = "SSN 123-45-6789, email jane.doe@example.com, phone (512) 555-0142, card 4111 1111 1111 1111, acct no 12345678";
        String out = redactor.redact(in);
        assertThat(out).doesNotContain("123-45-6789").doesNotContain("jane.doe@").doesNotContain("555-0142").doesNotContain("4111").doesNotContain("12345678");
        assertThat(out).contains("[ID]").contains("[EMAIL]").contains("[PHONE]").contains("[CARD]").contains("[ACCOUNT]");
        assertThat(redactor.containsPii("nothing sensitive here, income 41000")).isFalse();
    }

    @Test
    void hashingEmbeddingsAreDeterministicUnitLengthAndLexicallySensible() {
        HashingEmbeddingClient e = new HashingEmbeddingClient();
        float[] a = e.embed("KROGER #1182");
        float[] b = e.embed("KROGER #2231");
        float[] c = e.embed("T-MOBILE AUTOPAY");
        assertThat(a).hasSize(EmbeddingClient.DIMENSIONS);
        assertThat(e.embed("KROGER #1182")).containsExactly(a);
        double norm = 0;
        for (float x : a) {
            norm += x * x;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(cosine(a, b)).isGreaterThan(cosine(a, c));
    }

    @Test
    void offlineTemplateProducesAValidatableNoticeFromTheSameContext() {
        Map<String, Object> ctx = Map.of(
                "decision", "DECLINE", "basis", "CREDIT_POLICY", "score", 540, "pd", 0.31, "fileType", "thin",
                "reasons", List.of(Map.of("code", "R06", "text", "Income is irregular from month to month", "feature", "income_cv", "value", 0.7),
                        Map.of("code", "R11", "text", "Recent overdraft or insufficient-funds activity", "feature", "nsf_count_6m", "value", 3.0)),
                "strengths", List.of(Map.of("code", "R12", "label", "Rent payment regularity", "contribution", -0.3)),
                "fraud", Map.of("outcome", "PASS", "points", 0, "firedRules", List.of()),
                "verificationItems", List.of());
        LlmRequest req = new LlmRequest(LlmTask.ADVERSE_ACTION_NOTICE, "sys", "user", ctx, 500);
        LlmResponse resp = new OfflineTemplateLlmClient(mapper).complete(req);
        LlmOutputValidator.Result r = new LlmOutputValidator(mapper).validate(LlmTask.ADVERSE_ACTION_NOTICE, resp.text(), ctx);
        assertThat(r.errors()).isEmpty();
        assertThat(r.json().get("principal_reasons")).hasSize(2);

        LlmResponse summary = new OfflineTemplateLlmClient(mapper).complete(new LlmRequest(LlmTask.UNDERWRITER_SUMMARY, "s", "u", ctx, 500));
        assertThat(new LlmOutputValidator(mapper).validate(LlmTask.UNDERWRITER_SUMMARY, summary.text(), ctx).valid()).isTrue();
    }

    @Test
    void promptTemplatesRenderPlaceholdersAndFallBackToNa() {
        PromptTemplateService t = new PromptTemplateService();
        assertThat(t.render("Decision: {{decision}} / {{missing}}", Map.of("decision", "APPROVE"))).isEqualTo("Decision: APPROVE / n/a");
        assertThat(t.system(LlmTask.ADVERSE_ACTION_NOTICE)).contains("Use ONLY the principal reasons provided");
        assertThat(t.user(LlmTask.COPILOT_ANSWER, Map.of("question", "What is the refer band?"))).contains("What is the refer band?");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
