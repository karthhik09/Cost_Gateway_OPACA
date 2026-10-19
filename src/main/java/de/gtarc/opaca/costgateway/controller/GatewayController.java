package de.gtarc.opaca.costgateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import de.gtarc.opaca.costgateway.model.GatewayResponse;
import de.gtarc.opaca.costgateway.model.TokenUsage;
import de.gtarc.opaca.costgateway.service.BudgetSharingService;
import de.gtarc.opaca.costgateway.service.ModelRoutingService;
import de.gtarc.opaca.costgateway.service.TokenCostPredictor;
import de.gtarc.opaca.costgateway.service.TokenTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Core gateway controller that intercepts OPACA invoke calls.
 *
 * <p>Mirrors OPACA's own {@code POST /invoke/{action}} and
 * {@code POST /invoke/{action}/{agentId}} routes so that clients can swap
 * the host from OPACA (port 8000) to the gateway (port 8080) with zero
 * other changes.
 *
 * <p>Before forwarding, the gateway now applies three novelty features:
 * <ol>
 *   <li><b>Predictive Token Costing (Feature 3)</b> — estimates tokens
 *       pre-flight and pre-emptively rejects requests guaranteed to exceed
 *       the remaining budget.</li>
 *   <li><b>Dynamic Budget Sharing (Feature 2)</b> — if the agent is starving
 *       (approaching or past the hard-reject threshold), attempts to borrow
 *       tokens from the global shared pool before issuing HTTP 429.</li>
 *   <li><b>Complexity-Aware Routing (Feature 1)</b> — integrated inside
 *       {@link ModelRoutingService}: simple prompts are fast-tracked to
 *       Tier 2, preserving Tier-1 quota for complex reasoning tasks.</li>
 * </ol>
 *
 * Response headers on every 2xx call:
 * <ul>
 *   <li>{@code X-Gateway-Tier}         — PRIMARY | ECONOMY | FALLBACK</li>
 *   <li>{@code X-Gateway-Model}        — model identifier used</li>
 *   <li>{@code X-Tokens-This-Call}     — tokens consumed by this call</li>
 *   <li>{@code X-Tokens-Window}        — cumulative tokens in current budget window</li>
 *   <li>{@code X-Budget-Usage-Pct}     — usage percentage (e.g. 82.3)</li>
 *   <li>{@code X-Predicted-Tokens}     — pre-flight token estimate</li>
 *   <li>{@code X-Pool-Size}            — current global shared pool size</li>
 * </ul>
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequiredArgsConstructor
@Tag(name = "Invoke", description = "Cost-aware OPACA invoke proxy")
public class GatewayController {

    private final ModelRoutingService router;
    private final TokenTrackingService tracker;
    private final TokenCostPredictor predictor;
    private final BudgetSharingService budgetSharing;

    // ── Endpoints ──────────────────────────────────────────────────────────────

    @PostMapping("/invoke/{action}")
    @Operation(
        summary = "Invoke action (any agent)",
        description = "Intercepts OPACA invoke, enforces token budget, routes to the cheapest available tier.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful invocation"),
            @ApiResponse(responseCode = "429", description = "Token budget exhausted — retry after window reset",
                content = @Content(examples = @ExampleObject(
                    value = "{\"errorCode\":\"TOKEN_BUDGET_EXCEEDED\",\"error\":\"Token budget exhausted for agent 'default'\",\"tier\":\"REJECTED\"}"))),
            @ApiResponse(responseCode = "422", description = "Pre-emptive rejection — predicted tokens exceed remaining budget",
                content = @Content(examples = @ExampleObject(
                    value = "{\"errorCode\":\"PREDICTED_BUDGET_EXCEEDED\",\"error\":\"Predicted 1200 tokens but only 800 remain\",\"tier\":\"REJECTED\"}"))),
            @ApiResponse(responseCode = "502", description = "All tiers failed")
        }
    )
    public ResponseEntity<?> invoke(
            @PathVariable @Parameter(description = "OPACA action name") String action,
            @RequestBody Map<String, JsonNode> parameters,
            @RequestParam(required = false, defaultValue = "-1") int timeout
    ) {
        return handleInvoke(action, parameters, null, timeout);
    }

    @PostMapping("/invoke/{action}/{agentId}")
    @Operation(
        summary = "Invoke action (specific agent)",
        description = "Same as /invoke/{action} but scoped to a specific OPACA agent ID."
    )
    public ResponseEntity<?> invoke(
            @PathVariable @Parameter(description = "OPACA action name") String action,
            @PathVariable @Parameter(description = "OPACA agent ID") String agentId,
            @RequestBody Map<String, JsonNode> parameters,
            @RequestParam(required = false, defaultValue = "-1") int timeout
    ) {
        return handleInvoke(action, parameters, agentId, timeout);
    }

    // ── Core logic ─────────────────────────────────────────────────────────────

    private ResponseEntity<?> handleInvoke(
            String action, Map<String, JsonNode> parameters,
            String agentId, int timeout) {

        String effectiveAgent = agentId != null ? agentId : "default";

        try {
            double usagePct = tracker.getBudgetUsagePercent(effectiveAgent);
            long used        = tracker.getTokensUsed(effectiveAgent);
            long budget      = tracker.getTokenBudget(effectiveAgent);
            long resetIn     = tracker.getBudgetResetInSeconds(effectiveAgent);

            // ── Feature 2: Dynamic Budget Sharing — borrow if starving ─────────
            // Capture the ORIGINAL usage% BEFORE borrowing. This value is passed
            // to the router so FALLBACK (Gemini) can be reached even after the
            // budget is extended via pool borrowing.
            double preBorrowPct = usagePct;

            if (usagePct >= 100.0) {
                long borrowed = budgetSharing.tryBorrow(effectiveAgent);
                if (borrowed > 0) {
                    tracker.setTokenBudget(effectiveAgent, budget + borrowed);
                    budget   = budget + borrowed;
                    // Note: we intentionally do NOT update usagePct here.
                    // preBorrowPct is kept for routing; the new budget is in Redis.
                    log.info("Agent [{}] borrowed {} tokens from pool — new budget={} pre-borrow-usage={}%",
                            effectiveAgent, borrowed, budget, String.format("%.1f", preBorrowPct));
                }
            }

            // ── Hard stop at 150% over budget — return 429 ────────────────────
            if (usagePct >= 150.0) {
                tracker.recordThrottle(effectiveAgent);
                log.warn("HARD REJECT agent={} usage={}%", effectiveAgent, usagePct);

                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After",        String.valueOf(resetIn))
                        .header("X-Gateway-Tier",     "REJECTED")
                        .header("X-Tokens-Window",    String.valueOf(used))
                        .header("X-Budget-Usage-Pct", String.format("%.1f", usagePct))
                        .header("X-Pool-Size",         String.valueOf(budgetSharing.getPoolSize()))
                        .body(GatewayResponse.builder()
                                .errorCode("TOKEN_BUDGET_EXCEEDED")
                                .error("Token budget exhausted for agent '" + effectiveAgent
                                        + "' (used " + used + " / " + budget + " tokens). "
                                        + "Pool also exhausted. Resets in " + resetIn + "s.")
                                .agentId(effectiveAgent)
                                .tier("REJECTED")
                                .build());
            }

            // ── Feature 3: Predictive Token Costing ───────────────────────────
            // Estimate tokens before calling upstream. Pre-emptively reject
            // with 422 only if predicted tokens would exceed the remaining hard limit.
            TokenCostPredictor.PredictionResult prediction =
                    predictor.shouldReject(effectiveAgent, used, budget, action, parameters);

            if (prediction.shouldReject) {
                log.warn("PRE-EMPTIVE REJECT agent={} predicted={} remaining={}",
                        effectiveAgent, prediction.predictedTokens, prediction.remainingBudget);
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .header("X-Predicted-Tokens",  String.valueOf(prediction.predictedTokens))
                        .header("X-Remaining-Budget",  String.valueOf(prediction.remainingBudget))
                        .header("Retry-After",          String.valueOf(resetIn))
                        .header("X-Gateway-Tier",       "REJECTED")
                        .body(GatewayResponse.builder()
                                .errorCode("PREDICTED_BUDGET_EXCEEDED")
                                .error("Predicted " + prediction.predictedTokens + " tokens but only "
                                        + prediction.remainingBudget + " remain before hard limit for agent '"
                                        + effectiveAgent + "'. Estimated cost: $"
                                        + String.format("%.6f", prediction.predictedCostUsd)
                                        + ". Resets in " + resetIn + "s.")
                                .agentId(effectiveAgent)
                                .tier("REJECTED")
                                .build());
            }

            // ── Feature 2 (continued): Donation on idle ────────────────────────
            // If the agent is well within budget, donate surplus to the pool.
            // We call this before routing so idle agents contribute immediately.
            budgetSharing.tryDonate(effectiveAgent, used, budget);

            // ── Route to appropriate tier (Feature 1 integrated in router) ─────
            // Pass preBorrowPct so the router uses the agent's real financial state
            // (before Dynamic Budget Sharing extended the budget).
            GatewayResponse response = router.routeInvoke(action, parameters, agentId, timeout, preBorrowPct);
            response.setAgentId(effectiveAgent);

            // Record token usage in Redis + Prometheus
            if (response.getTokenUsage() != null) {
                tracker.recordUsage(effectiveAgent, response.getTokenUsage());
            }

            // Refresh usage after recording for accurate headers
            long newUsed  = tracker.getTokensUsed(effectiveAgent);
            double newPct = tracker.getBudgetUsagePercent(effectiveAgent);
            TokenUsage tu = response.getTokenUsage();

            return ResponseEntity.ok()
                    .header("X-Gateway-Tier",       response.getTier())
                    .header("X-Gateway-Model",       response.getModel())
                    .header("X-Tokens-This-Call",    tu != null ? String.valueOf(tu.getTotalTokens()) : "0")
                    .header("X-Tokens-Window",       String.valueOf(newUsed))
                    .header("X-Budget-Usage-Pct",    String.format("%.1f", newPct))
                    .header("X-Predicted-Tokens",    String.valueOf(prediction.predictedTokens))
                    .header("X-Pool-Size",            String.valueOf(budgetSharing.getPoolSize()))
                    .body(response);

        } catch (Exception e) {
            log.error("Gateway error for invoke/{} agent={}: {}", action, effectiveAgent, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(GatewayResponse.builder()
                            .errorCode("GATEWAY_ERROR")
                            .error(e.getMessage())
                            .agentId(effectiveAgent)
                            .build());
        }
    }
}
