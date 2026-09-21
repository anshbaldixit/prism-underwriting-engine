package dev.anshdixit.prism.monitoring;

import dev.anshdixit.prism.ai.AiInvocationRepository;
import dev.anshdixit.prism.scoring.Decision;
import dev.anshdixit.prism.scoring.DecisionRepository;
import dev.anshdixit.prism.underwriting.UnderwriterAction;
import dev.anshdixit.prism.underwriting.UnderwriterActionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Live operational view over this instance's decisions and model calls: decision mix, score distribution,
 * fraud-gate mix, override rate, and per-provider AI latency / validation / fallback rates.
 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final DecisionRepository decisions;
    private final AiInvocationRepository ai;
    private final UnderwriterActionRepository actions;

    public MonitoringController(DecisionRepository decisions, AiInvocationRepository ai, UnderwriterActionRepository actions) {
        this.decisions = decisions;
        this.ai = ai;
        this.actions = actions;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public Map<String, Object> summary() {
        List<Decision> all = decisions.findAllByOrderByCreatedAtDesc();
        Map<String, Long> byDecision = new TreeMap<>();
        Map<String, Long> byFraud = new TreeMap<>();
        Map<String, Long> byBasis = new TreeMap<>();
        Map<String, Long> scoreBands = new TreeMap<>();
        double pdSum = 0;
        long latencySum = 0;
        for (Decision d : all) {
            byDecision.merge(d.getDecision(), 1L, Long::sum);
            byFraud.merge(d.getFraudOutcome(), 1L, Long::sum);
            byBasis.merge(d.getBasis(), 1L, Long::sum);
            int band = Math.max(400, Math.min(760, (d.getScore() / 40) * 40));
            scoreBands.merge(band + "-" + (band + 39), 1L, Long::sum);
            pdSum += d.getPd();
            latencySum += d.getScoringLatencyMs();
        }
        long overrides = actions.countByActionIn(List.of(UnderwriterAction.Action.APPROVE_OVERRIDE, UnderwriterAction.Action.DECLINE_OVERRIDE));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisions", all.size());
        m.put("byDecision", byDecision);
        m.put("byBasis", byBasis);
        m.put("byFraudOutcome", byFraud);
        m.put("scoreBands", scoreBands);
        m.put("meanPd", all.isEmpty() ? null : pdSum / all.size());
        m.put("meanScoringLatencyMs", all.isEmpty() ? null : (double) latencySum / all.size());
        m.put("overrides", overrides);
        m.put("overrideRate", all.isEmpty() ? null : (double) overrides / all.size());
        m.put("aiInvocations", ai.stats().stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", s.getProvider());
            row.put("task", s.getTask());
            row.put("calls", s.getCalls());
            row.put("avgLatencyMs", s.getAvgLatencyMs());
            row.put("validRate", s.getCalls() == 0 ? null : (double) s.getValidCount() / s.getCalls());
            row.put("fallbackRate", s.getCalls() == 0 ? null : (double) s.getFallbackCount() / s.getCalls());
            return row;
        }).toList());
        return m;
    }
}
