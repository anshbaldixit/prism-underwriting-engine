package dev.anshdixit.prism.ai;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The output-side guardrail. A response is accepted only if it (1) is well-formed JSON of the task's schema,
 * (2) is <em>consistent with the model</em> - every reason code it states is one the scorecard actually
 * produced for this applicant, no more and no fewer, and the decision it restates is the real one - and
 * (3) contains no protected-class language. Anything else is rejected and the caller falls back to the
 * deterministic template, so a hallucinated reason can never reach a customer.
 */
@Component
public class LlmOutputValidator {

    /** Word-boundary matches so "manage"/"average" do not trip the age check. */
    private static final Pattern PROTECTED_CLASS = Pattern.compile(
            "(?i)\\b(age|aged|gender|sex|race|racial|religion|religious|national origin|nationality|ethnic|ethnicity|marital|married|divorced|disabilit\\w*|pregnan\\w*|welfare|public assistance)\\b");
    private static final Pattern PROMISSORY = Pattern.compile("(?i)\\b(guarantee[ds]?|will be approved|certainly approved|promise)\\b");

    private final JsonMapper mapper;

    public LlmOutputValidator(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(boolean valid, List<String> errors, JsonNode json) {
        public static Result invalid(String error) {
            return new Result(false, List.of(error), null);
        }
    }

    @SuppressWarnings("unchecked")
    public Result validate(LlmTask task, String rawText, Map<String, Object> context) {
        JsonNode json;
        try {
            json = mapper.readTree(stripFences(rawText));
        } catch (JacksonException e) {
            return Result.invalid("Response is not valid JSON: " + e.getOriginalMessage());
        }
        if (json == null || !json.isObject()) {
            return Result.invalid("Response is not a JSON object");
        }
        List<String> errors = new ArrayList<>();
        String flat = json.toString();
        if (PROTECTED_CLASS.matcher(flat).find()) {
            errors.add("Output references a protected characteristic");
        }
        switch (task) {
            case ADVERSE_ACTION_NOTICE -> validateNotice(json, context, errors);
            case UNDERWRITER_SUMMARY -> validateSummary(json, context, errors);
            case COPILOT_ANSWER -> validateCopilot(json, context, errors);
        }
        if (task != LlmTask.COPILOT_ANSWER && PROMISSORY.matcher(flat).find()) {
            errors.add("Output contains promissory language");
        }
        return new Result(errors.isEmpty(), errors, json);
    }

    @SuppressWarnings("unchecked")
    private void validateNotice(JsonNode json, Map<String, Object> ctx, List<String> errors) {
        requireEquals(json, "decision", (String) ctx.get("decision"), errors);
        requireText(json, "summary", 700, errors);
        requireText(json, "disclaimer", 400, errors);
        Set<String> allowed = allowedCodes(ctx);
        JsonNode reasons = json.get("principal_reasons");
        if (reasons == null || !reasons.isArray()) {
            errors.add("principal_reasons missing");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode r : reasons) {
            String code = r.path("code").asString(null);
            if (code == null || !allowed.contains(code)) {
                errors.add("Reason code not produced by the model: " + code);
            } else if (!seen.add(code)) {
                errors.add("Duplicate reason code: " + code);
            }
            if (r.path("explanation").asString("").isBlank() || r.path("explanation").asString("").length() > 300) {
                errors.add("Reason explanation missing or too long for " + code);
            }
        }
        if (!seen.containsAll(allowed)) {
            errors.add("Notice omits model reason codes: " + allowed.stream().filter(c -> !seen.contains(c)).toList());
        }
        JsonNode tips = json.get("improvement_tips");
        if (tips != null && (!tips.isArray() || tips.size() > 4)) {
            errors.add("improvement_tips must be an array of at most 4");
        }
    }

    private void validateSummary(JsonNode json, Map<String, Object> ctx, List<String> errors) {
        requireText(json, "headline", 200, errors);
        String rec = json.path("recommendation").asString("");
        if (!Set.of("APPROVE", "DECLINE", "REFER", "REQUEST_DOCS").contains(rec)) {
            errors.add("Invalid recommendation: " + rec);
        }
        Set<String> allowed = allowedCodes(ctx);
        JsonNode cited = json.get("cited_factors");
        if (cited != null && cited.isArray()) {
            for (JsonNode c : cited) {
                if (!allowed.contains(c.asString(""))) {
                    errors.add("cited_factors contains unknown code " + c.asString(""));
                }
            }
        }
        for (String arr : List.of("strengths", "risks", "verification_items")) {
            JsonNode a = json.get(arr);
            if (a == null || !a.isArray()) {
                errors.add(arr + " missing");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCopilot(JsonNode json, Map<String, Object> ctx, List<String> errors) {
        requireText(json, "answer", 1500, errors);
        String conf = json.path("confidence").asString("");
        if (!Set.of("high", "medium", "low").contains(conf)) {
            errors.add("Invalid confidence: " + conf);
        }
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) ctx.getOrDefault("policyChunks", List.of());
        Set<String> known = new HashSet<>();
        for (Map<String, Object> c : chunks) {
            known.add(c.get("doc") + "|" + c.get("section"));
        }
        JsonNode cites = json.get("citations");
        if (cites != null && cites.isArray()) {
            for (JsonNode c : cites) {
                String key = c.path("doc").asString("") + "|" + c.path("section").asString("");
                if (!known.contains(key)) {
                    errors.add("Citation to excerpt that was not provided: " + key);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> allowedCodes(Map<String, Object> ctx) {
        Set<String> allowed = new HashSet<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) ctx.getOrDefault("reasons", List.of())) {
            allowed.add((String) r.get("code"));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) ctx.getOrDefault("strengths", List.of())) {
            allowed.add((String) r.get("code"));
        }
        return allowed;
    }

    private static void requireText(JsonNode json, String field, int maxLen, List<String> errors) {
        String v = json.path(field).asString("");
        if (v.isBlank()) {
            errors.add(field + " missing");
        } else if (v.length() > maxLen) {
            errors.add(field + " exceeds " + maxLen + " characters");
        }
    }

    private static void requireEquals(JsonNode json, String field, String expected, List<String> errors) {
        String v = json.path(field).asString("");
        if (expected != null && !expected.equals(v)) {
            errors.add(field + " must be " + expected + " but was " + v);
        }
    }

    static String stripFences(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return start >= 0 && end > start ? t.substring(start, end + 1) : t;
    }
}
