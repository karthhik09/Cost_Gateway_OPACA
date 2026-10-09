package de.gtarc.opaca.costgateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import de.gtarc.opaca.costgateway.model.GatewayResponse;
import de.gtarc.opaca.costgateway.model.TokenUsage;
import de.gtarc.opaca.costgateway.service.ModelRoutingService;
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
 * <p>Before forwarding, the gateway:
 * <ol>
 *   <li>Checks the agent's current token budget usage from Redis.</li>
 *   <li>Routes to the appropriate tier (PRIMARY / ECONOMY / FALLBACK).</li>
 *   <li>Records token usage and cost after each successful call.</li>
 *   <li>Returns HTTP 429 when the budget is hard-exhausted (>= 150% of limit).</li>
 * </ol>
 *
 * Response headers on every 2xx call:
 * <ul>
 *   <li>{@code X-Gateway-Tier}   — PRIMARY | ECONOMY | FALLBACK</li>
 *   <li>{@code X-Gateway-Model}  — model identifier used</li>
 *   <li>{@code X-Tokens-This-Call} — tokens consumed by this call</li>
 *   <li>{@code X-Tokens-Window}  — cumulative tokens in current budget window</li>
 *   <li>{@code X-Budget-Usage-Pct} — usage percentage (e.g. 82.3)</li>
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

            // Hard stop at 150% over budget — return 429
            if (usagePct >= 150.0) {
                tracker.recordThrottle(effectiveAgent);
                log.warn("HARD REJECT agent={} usage={}%", effectiveAgent, usagePct);

                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After",        String.valueOf(resetIn))
                        .header("X-Gateway-Tier",     "REJECTED")
                        .header("X-Tokens-Window",    String.valueOf(used))
                        .header("X-Budget-Usage-Pct", String.format("%.1f", usagePct))
                        .body(GatewayResponse.builder()
                                .errorCode("TOKEN_BUDGET_EXCEEDED")
                                .error("Token budget exhausted for agent '" + effectiveAgent
                                        + "' (used " + used + " / " + budget + " tokens). "
                                        + "Resets in " + resetIn + "s.")
                                .agentId(effectiveAgent)
                                .tier("REJECTED")
                                .build());
            }

            // Route to appropriate tier
            GatewayResponse response = router.routeInvoke(action, parameters, agentId, timeout);
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
