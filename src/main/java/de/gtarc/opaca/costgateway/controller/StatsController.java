package de.gtarc.opaca.costgateway.controller;

import de.gtarc.opaca.costgateway.model.AgentUsageStats;
import de.gtarc.opaca.costgateway.service.TokenTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin REST API for token budget management and usage inspection.
 *
 * All endpoints are prefixed with /gateway to avoid collision with OPACA routes.
 */
@Slf4j
@RestController
@RequestMapping("/gateway")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Budget management and usage statistics")
public class StatsController {

    private final TokenTrackingService tracker;

    // ── Usage stats ────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "List all agent usage stats",
               description = "Returns token usage, cost, tier, and throttle counts for every agent seen in the current budget window.")
    public ResponseEntity<List<AgentUsageStats>> getAllStats() {
        return ResponseEntity.ok(tracker.getAllAgentStats());
    }

    @GetMapping("/stats/{agentId}")
    @Operation(summary = "Get usage stats for a specific agent")
    public ResponseEntity<AgentUsageStats> getStats(@PathVariable String agentId) {
        return ResponseEntity.ok(tracker.getAgentStats(agentId));
    }

    // ── Budget management ──────────────────────────────────────────────────────

    @PutMapping("/budget/{agentId}")
    @Operation(summary = "Set token budget for an agent",
               description = "Sets a custom per-agent token budget. Overrides the global default. Takes effect immediately.")
    public ResponseEntity<Map<String, Object>> setBudget(
            @PathVariable String agentId,
            @RequestBody Map<String, Long> body) {

        Long budget = body.get("budget");
        if (budget == null || budget <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'budget' must be a positive integer"));
        }
        tracker.setTokenBudget(agentId, budget);
        log.info("Budget for agent [{}] set to {} tokens via API", agentId, budget);
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "budget", budget,
                "message", "Budget updated successfully"
        ));
    }

    @DeleteMapping("/budget/{agentId}/reset")
    @Operation(summary = "Reset usage counters for an agent",
               description = "Clears all Redis counters for the agent. Useful for testing or after a manual budget override.")
    public ResponseEntity<Map<String, Object>> resetBudget(@PathVariable String agentId) {
        tracker.resetUsage(agentId);
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "message", "Usage counters reset successfully"
        ));
    }

    // ── Gateway info ───────────────────────────────────────────────────────────

    @GetMapping("/info")
    @Operation(summary = "Gateway metadata",
               description = "Returns service name, version, and status. Useful for Kubernetes liveness probes.")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of(
                "service",     "OPACA Cost-Aware Gateway",
                "version",     "1.0.0",
                "status",      "running",
                "description", "Cost-aware API gateway with multi-tier LLM routing for OPACA"
        ));
    }
}
