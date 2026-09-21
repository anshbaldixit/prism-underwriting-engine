package dev.anshdixit.prism.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmOutputValidatorTest {

    final LlmOutputValidator validator = new LlmOutputValidator(JsonMapper.builder().build());

    final Map<String, Object> ctx = Map.of(
            "decision", "DECLINE",
            "reasons", List.of(Map.of("code", "R06", "text", "Income is irregular"), Map.of("code", "R11", "text", "Overdrafts")),
            "strengths", List.of(Map.of("code", "R12", "label", "Rent regular")));

    private static String notice(String decision, String codes, String extra) {
        return "{\"decision\":\"" + decision + "\",\"summary\":\"We could not approve your application." + extra + "\"," +
                "\"principal_reasons\":[" + codes + "],\"improvement_tips\":[\"Keep a small buffer.\"],\"disclaimer\":\"You may request the specific reasons within 60 days.\"}";
    }

    @Test
    void acceptsANoticeThatRestatesExactlyTheModelReasons() {
        String ok = notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"Your income varied.\"},{\"code\":\"R11\",\"explanation\":\"Two overdraft fees.\"}", "");
        LlmOutputValidator.Result r = validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, ok, ctx);
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
    }

    @Test
    void rejectsHallucinatedReasonCode() {
        String bad = notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"x\"},{\"code\":\"R11\",\"explanation\":\"x\"},{\"code\":\"R01\",\"explanation\":\"Low bureau score.\"}", "");
        LlmOutputValidator.Result r = validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, bad, ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("R01"));
    }

    @Test
    void rejectsOmittedReasonCode() {
        String bad = notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"x\"}", "");
        LlmOutputValidator.Result r = validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, bad, ctx);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("omits"));
    }

    @Test
    void rejectsADecisionThatDisagreesWithTheEngine() {
        String bad = notice("APPROVE", "{\"code\":\"R06\",\"explanation\":\"x\"},{\"code\":\"R11\",\"explanation\":\"x\"}", "");
        assertThat(validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, bad, ctx).errors()).anyMatch(e -> e.contains("decision"));
    }

    @Test
    void rejectsProtectedClassAndPromissoryLanguage() {
        String bad = notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"x\"},{\"code\":\"R11\",\"explanation\":\"x\"}", " Applicants of your age are usually declined, but you will be approved next time.");
        LlmOutputValidator.Result r = validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, bad, ctx);
        assertThat(r.errors()).anyMatch(e -> e.contains("protected")).anyMatch(e -> e.contains("promissory"));
    }

    @Test
    void wordBoundaryMatchingDoesNotTripOnManageOrAverage() {
        String ok = notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"Manage your average balance.\"},{\"code\":\"R11\",\"explanation\":\"x\"}", "");
        assertThat(validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, ok, ctx).valid()).isTrue();
    }

    @Test
    void toleratesMarkdownFencesAndRejectsNonJson() {
        String fenced = "```json\n" + notice("DECLINE", "{\"code\":\"R06\",\"explanation\":\"x\"},{\"code\":\"R11\",\"explanation\":\"x\"}", "") + "\n```";
        assertThat(validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, fenced, ctx).valid()).isTrue();
        assertThat(validator.validate(LlmTask.ADVERSE_ACTION_NOTICE, "Sorry, I cannot help with that.", ctx).valid()).isFalse();
    }

    @Test
    void copilotMayOnlyCiteRetrievedExcerpts() {
        Map<String, Object> cctx = Map.of("policyChunks", List.of(Map.of("doc", "credit-policy", "section", "Decision bands", "content", "...")));
        String good = "{\"answer\":\"Bands are calibrated to 6%.\",\"citations\":[{\"doc\":\"credit-policy\",\"section\":\"Decision bands\"}],\"confidence\":\"high\"}";
        String bad = "{\"answer\":\"See the manual.\",\"citations\":[{\"doc\":\"secret-manual\",\"section\":\"1\"}],\"confidence\":\"high\"}";
        assertThat(validator.validate(LlmTask.COPILOT_ANSWER, good, cctx).valid()).isTrue();
        assertThat(validator.validate(LlmTask.COPILOT_ANSWER, bad, cctx).errors()).anyMatch(e -> e.contains("not provided"));
    }
}
