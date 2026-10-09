package de.gtarc.opaca.costgateway.controller;

import de.gtarc.opaca.costgateway.model.AgentUsageStats;
import de.gtarc.opaca.costgateway.service.BudgetSharingService;
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
 * Admin REST API for token budget management, usage inspection,
 * and global budget sharing pool operations.
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
    private final BudgetSharingService budgetSharing;

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

    // ── Dynamic Budget Sharing pool (Feature 2) ────────────────────────────────

    @GetMapping("/pool")
    @Operation(
        summary = "Query global budget sharing pool",
        description = "Returns the current size of the global token sharing pool. "
                    + "Agents donate surplus tokens to this pool and starving agents borrow from it "
                    + "(Dynamic Budget Sharing — novelty feature 2).")
    public ResponseEntity<Map<String, Object>> getPool() {
        budgetSharing.ensurePoolInitialised();
        return ResponseEntity.ok(Map.of(
                "globalPoolTokens", budgetSharing.getPoolSize(),
                "description",      "Global shared token pool for inter-agent budget lending"
        ));
    }

    @PostMapping("/pool/borrow/{agentId}")
    @Operation(
        summary = "Manually trigger a borrow from the global pool",
        description = "Allows an agent to manually request tokens from the global sharing pool. "
                    + "Returns the actual number borrowed (may be 0 if pool is empty).")
    public ResponseEntity<Map<String, Object>> manualBorrow(@PathVariable String agentId) {
        long borrowed = budgetSharing.tryBorrow(agentId);
        if (borrowed > 0) {
            long currentBudget = tracker.getTokenBudget(agentId);
            tracker.setTokenBudget(agentId, currentBudget + borrowed);
        }
        return ResponseEntity.ok(Map.of(
                "agentId",          agentId,
                "tokensBorrowed",   borrowed,
                "poolRemaining",    budgetSharing.getPoolSize(),
                "message",          borrowed > 0
                                        ? "Successfully borrowed " + borrowed + " tokens from global pool"
                                        : "Global pool is empty — no tokens available to borrow"
        ));
    }

    @PostMapping("/pool/donate/{agentId}")
    @Operation(
        summary = "Manually trigger a donation to the global pool",
        description = "Forces an agent to donate a fraction of its remaining budget to the global sharing pool.")
    public ResponseEntity<Map<String, Object>> manualDonate(@PathVariable String agentId) {
        long used   = tracker.getTokensUsed(agentId);
        long budget = tracker.getTokenBudget(agentId);
        budgetSharing.tryDonate(agentId, used, budget);
        return ResponseEntity.ok(Map.of(
                "agentId",       agentId,
                "poolSize",      budgetSharing.getPoolSize(),
                "message",       "Donation attempt completed (actual donation depends on idle threshold)"
        ));
    }

    // ── Gateway info ───────────────────────────────────────────────────────────

    @GetMapping("/info")
    @Operation(summary = "Gateway metadata",
               description = "Returns service name, version, and status. Useful for Kubernetes liveness probes.")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of(
                "service",     "OPACA Cost-Aware Gateway",
                "version",     "2.0.0",
                "status",      "running",
                "description", "Cost-aware API gateway with multi-tier LLM routing, " +
                               "complexity-aware routing, dynamic budget sharing, and predictive token costing"
        ));
    }
}
