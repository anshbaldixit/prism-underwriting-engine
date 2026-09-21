package dev.anshdixit.prism.ai.offline;

import dev.anshdixit.prism.ai.LlmClient;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic "model" that renders the same JSON shapes the real LLM is asked for, straight from the
 * structured context. It is (a) the zero-credential demo path and (b) the guaranteed fallback when the
 * live model is down, times out, or fails output validation - so the product never depends on an LLM
 * being available to issue a compliant notice.
 */
public class OfflineTemplateLlmClient implements LlmClient {

    private final JsonMapper mapper;

    public OfflineTemplateLlmClient(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long t0 = System.nanoTime();
        Map<String, Object> out = switch (request.task()) {
            case ADVERSE_ACTION_NOTICE -> adverseActionNotice(request.context());
            case UNDERWRITER_SUMMARY -> underwriterSummary(request.context());
            case COPILOT_ANSWER -> copilotAnswer(request.context());
        };
        String json = mapper.writeValueAsString(out);
        return new LlmResponse(json, providerName(), "template-v1", 0, 0, (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public String providerName() {
        return "offline-template";
    }

    @Override
    public boolean isOffline() {
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adverseActionNotice(Map<String, Object> ctx) {
        String decision = (String) ctx.get("decision");
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) ctx.getOrDefault("reasons", List.of());
        List<String> verification = (List<String>) ctx.getOrDefault("verificationItems", List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", decision);
        out.put("summary", switch (decision) {
            case "APPROVE" -> String.format("Good news - your application has been approved for a credit line of $%,.0f at %.2f%% APR. The summary below explains the main factors we considered.",
                    num(ctx.get("creditLimit")), num(ctx.get("apr")));
            case "REFER" -> "Your application needs a short additional review before we can give you a final answer. " +
                    (verification.isEmpty() ? "An underwriter will look at the factors below." : "To complete it we need: " + String.join("; ", verification) + ".");
            default -> "We were not able to approve your application at this time. The principal reasons, in order of importance, are listed below along with what you can do about them.";
        });
        List<Map<String, String>> principal = new ArrayList<>();
        List<String> tips = new ArrayList<>();
        for (Map<String, Object> r : reasons) {
            principal.add(Map.of("code", (String) r.get("code"), "explanation", (String) r.get("text") + explainValue(r)));
            String tip = tipFor((String) r.get("code"));
            if (tip != null && tips.size() < 4) {
                tips.add(tip);
            }
        }
        out.put("principal_reasons", principal);
        out.put("improvement_tips", tips);
        out.put("disclaimer", "This notice was generated from the factors used by our scoring model. You have the right to request the specific reasons for this decision and to obtain a free copy of any consumer report we used, within 60 days.");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> underwriterSummary(Map<String, Object> ctx) {
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) ctx.getOrDefault("reasons", List.of());
        List<Map<String, Object>> positives = (List<Map<String, Object>>) ctx.getOrDefault("strengths", List.of());
        Map<String, Object> fraud = (Map<String, Object>) ctx.getOrDefault("fraud", Map.of());
        List<String> verification = (List<String>) ctx.getOrDefault("verificationItems", List.of());
        String decision = (String) ctx.get("decision");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headline", String.format("%s file, score %s (PD %.1f%%) - engine decision %s via %s",
                capitalise((String) ctx.getOrDefault("fileType", "unknown")), ctx.get("score"), num(ctx.get("pd")) * 100, decision, ctx.get("basis")));
        out.put("strengths", positives.stream().map(p -> (String) p.get("label") + " (" + p.get("code") + ")").toList());
        List<String> risks = new ArrayList<>(reasons.stream().map(r -> (String) r.get("text") + " (" + r.get("code") + ")").toList());
        List<String> fired = (List<String>) fraud.getOrDefault("firedRules", List.of());
        if (!fired.isEmpty()) {
            risks.add("Fraud gate " + fraud.get("outcome") + ": " + String.join("; ", fired));
        }
        out.put("risks", risks);
        out.put("verification_items", verification);
        out.put("recommendation", switch (decision) {
            case "APPROVE" -> "APPROVE";
            case "DECLINE" -> "DECLINE";
            default -> verification.isEmpty() ? "REFER" : "REQUEST_DOCS";
        });
        out.put("cited_factors", reasons.stream().map(r -> (String) r.get("code")).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copilotAnswer(Map<String, Object> ctx) {
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) ctx.getOrDefault("policyChunks", List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        StringBuilder answer = new StringBuilder("(Offline mode - retrieval only.) ");
        if (ctx.get("applicationSummary") != null) {
            answer.append("Application context: ").append(ctx.get("applicationSummary")).append(" ");
        }
        if (chunks.isEmpty()) {
            answer.append("No policy passage matched the question closely enough to cite.");
        } else {
            answer.append("The most relevant policy passages are: ");
            for (Map<String, Object> c : chunks.subList(0, Math.min(2, chunks.size()))) {
                answer.append("[").append(c.get("doc")).append(" > ").append(c.get("section")).append("] ")
                        .append(truncate((String) c.get("content"), 280)).append(" ");
            }
        }
        out.put("answer", answer.toString().trim());
        out.put("citations", chunks.stream().limit(2).map(c -> Map.of("doc", c.get("doc"), "section", c.get("section"))).toList());
        out.put("confidence", chunks.isEmpty() ? "low" : "medium");
        return out;
    }

    private static String explainValue(Map<String, Object> r) {
        Object v = r.get("value");
        if (v == null) {
            return " (not observed in the data provided)";
        }
        return ".";
    }

    private static String tipFor(String code) {
        return switch (code) {
            case "R01", "R05" -> "Keep all existing accounts current for the next 6-12 months; on-time history is the fastest way to raise a bureau score.";
            case "R02", "R03", "R08" -> "Keep your linked account active - each additional month of verified history strengthens a future application.";
            case "R04" -> "Avoid applying for several credit products in a short period.";
            case "R06" -> "Where possible, route income through one account so its regularity can be seen.";
            case "R07", "R18" -> "Consider applying for a smaller amount relative to your verified income.";
            case "R09", "R10" -> "Building a small buffer in your account (even one week of expenses) reduces risk signals.";
            case "R11" -> "Setting up low-balance alerts helps avoid overdraft and returned-item fees.";
            case "R12", "R13", "R14" -> "Paying rent, utilities and phone bills from the linked account each month builds a positive record.";
            case "R15" -> "Reducing existing monthly obligations will improve the obligations-to-income ratio.";
            case "R16", "R17" -> "Reducing discretionary or gambling-related spending relative to income improves affordability.";
            default -> null;
        };
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
