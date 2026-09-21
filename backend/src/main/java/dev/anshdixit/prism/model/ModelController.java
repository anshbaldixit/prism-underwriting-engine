package dev.anshdixit.prism.model;

import dev.anshdixit.prism.ai.GuardrailService;
import dev.anshdixit.prism.fraud.FraudRules;
import dev.anshdixit.prism.scoring.Scorecard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only transparency endpoints: the model card, the scorecard itself, the fraud rules and the AI configuration. */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private final ModelArtifactsConfig.ModelCard card;
    private final Scorecard scorecard;
    private final FraudRules fraudRules;
    private final GuardrailService guardrails;

    public ModelController(ModelArtifactsConfig.ModelCard card, Scorecard scorecard, FraudRules fraudRules, GuardrailService guardrails) {
        this.card = card;
        this.scorecard = scorecard;
        this.fraudRules = fraudRules;
        this.guardrails = guardrails;
    }

    @GetMapping("/card")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public JsonNode card() {
        return card.json();
    }

    @GetMapping("/scorecard")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public Map<String, Object> scorecard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelId", scorecard.modelId());
        m.put("version", scorecard.version());
        m.put("trainedAt", scorecard.trainedAt());
        m.put("algorithm", scorecard.algorithm());
        m.put("target", scorecard.target());
        m.put("scaling", scorecard.scaling());
        m.put("decisionPolicy", scorecard.decisionPolicy());
        m.put("reasonCodes", scorecard.reasonCodes());
        List<Map<String, Object>> features = scorecard.features().stream().map(f -> {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("label", f.label());
            fm.put("modality", f.modality());
            fm.put("reasonCode", f.reasonCode());
            fm.put("coefficient", f.coefficient());
            fm.put("informationValue", f.informationValue());
            fm.put("missingWoe", f.missingWoe());
            fm.put("bins", f.bins());
            return fm;
        }).toList();
        m.put("features", features);
        return m;
    }

    @GetMapping("/fraud-rules")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public FraudRules fraudRules() {
        return fraudRules;
    }

    @GetMapping("/ai-config")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public Map<String, Object> aiConfig() {
        return guardrails.describe();
    }
}
