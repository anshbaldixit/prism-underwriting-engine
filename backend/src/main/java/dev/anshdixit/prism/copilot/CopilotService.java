package dev.anshdixit.prism.copilot;

import dev.anshdixit.prism.ai.GuardrailService;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmTask;
import dev.anshdixit.prism.ai.PromptTemplateService;
import dev.anshdixit.prism.application.ApplicationDetail;
import dev.anshdixit.prism.application.ApplicationService;
import dev.anshdixit.prism.config.PrismProperties;
import dev.anshdixit.prism.knowledge.PolicySearchService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retrieval-orchestrated underwriting copilot. Deterministic retrieval first (application facts, similar
 * cohort, top policy sections by vector search), then one guardrailed generation that may only cite what was
 * retrieved. No tool-calling loop: in a regulated workflow, predictable context beats open-ended agency.
 */
@Service
public class CopilotService {

    private final ApplicationService applications;
    private final PolicySearchService policy;
    private final PromptTemplateService prompts;
    private final GuardrailService guardrails;
    private final int maxTokens;

    public CopilotService(ApplicationService applications, PolicySearchService policy, PromptTemplateService prompts, GuardrailService guardrails, PrismProperties props) {
        this.applications = applications;
        this.policy = policy;
        this.prompts = prompts;
        this.guardrails = guardrails;
        this.maxTokens = props.ai().maxOutputTokens();
    }

    public record Answer(JsonNode answer, String provider, String model, boolean fallbackUsed, List<PolicySearchService.Chunk> retrieved, long latencyMs) {
    }

    /** Sections every underwriting question must be answered against, whatever the vector search returns. */
    private static final List<String[]> PINNED_SECTIONS = List.of(
            new String[]{"credit-policy", "Prohibited bases"},
            new String[]{"responsible-ai", "Roles of each component"});

    public Answer ask(UUID applicationId, String question) {
        List<PolicySearchService.Chunk> chunks = new ArrayList<>(policy.search(question, 8));
        for (String[] pinned : PINNED_SECTIONS) {
            if (chunks.stream().noneMatch(c -> c.doc().equals(pinned[0]) && c.section().equals(pinned[1]))) {
                policy.section(pinned[0], pinned[1]).ifPresent(chunks::add);
            }
        }
        String appSummary = "no application selected";
        String cohort = "not available";
        if (applicationId != null) {
            ApplicationDetail d = applications.get(applicationId, false);
            appSummary = summarise(d);
            if (d.similar() != null && d.similar().size() > 0) {
                cohort = String.format("%d nearest historical applicants: %.0f%% approved, %.1f%% defaulted within 12 months",
                        d.similar().size(), d.similar().approvalRate() * 100, d.similar().defaultRate() * 100);
            }
        }
        List<Map<String, Object>> chunkCtx = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (PolicySearchService.Chunk c : chunks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("doc", c.doc());
            m.put("section", c.section());
            m.put("content", c.content());
            chunkCtx.add(m);
            block.append("[doc: ").append(c.doc()).append(" | section: ").append(c.section()).append("]\n").append(c.content()).append("\n\n");
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("question", question);
        ctx.put("applicationSummary", appSummary);
        ctx.put("policyChunks", chunkCtx);
        Map<String, Object> vars = new HashMap<>();
        vars.put("question", question);
        vars.put("applicationSummary", appSummary);
        vars.put("similarCohort", cohort);
        vars.put("policyChunksBlock", block.toString().trim());
        GuardrailService.Guarded g = guardrails.generate(new LlmRequest(LlmTask.COPILOT_ANSWER, prompts.system(LlmTask.COPILOT_ANSWER),
                prompts.user(LlmTask.COPILOT_ANSWER, vars), ctx, maxTokens), applicationId);
        return new Answer(g.json(), g.provider(), g.model(), g.fallbackUsed(), chunks, g.latencyMs());
    }

    /** Facts only - no name, contact or identifier ever enters the prompt. */
    static String summarise(ApplicationDetail d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.fileType()).append(" file, bank ").append(d.bankLinked() ? "linked" : "not linked")
                .append(", requested $").append(d.loan().requestedAmount().toPlainString())
                .append(", stated income $").append(d.applicant().statedAnnualIncome().toPlainString());
        if (d.decision() != null) {
            sb.append("; engine decision ").append(d.decision().decision()).append(" (").append(d.decision().basis()).append(")")
                    .append(", score ").append(d.decision().score()).append(", PD ").append(String.format("%.1f%%", d.decision().pd() * 100))
                    .append(", fraud gate ").append(d.decision().fraud().outcome());
            if (d.decision().creditLimit() != null) {
                sb.append("; offer: credit line $").append(d.decision().creditLimit().toPlainString()).append(" at ").append(d.decision().apr()).append("% APR");
            }
            if (d.cashflow() != null && d.cashflow().features().get("monthlyIncome") != null) {
                sb.append("; verified monthly income $").append(d.cashflow().features().get("monthlyIncome"));
            }
            if (!d.decision().verificationItems().isEmpty()) {
                sb.append("; verification items: ").append(String.join(", ", d.decision().verificationItems()));
            }
            if (!d.decision().reasonCodes().isEmpty()) {
                sb.append("; principal reasons: ");
                d.decision().reasonCodes().forEach(r -> sb.append(r.code()).append(" ").append(r.text()).append("; "));
            }
        }
        return sb.toString();
    }
}
