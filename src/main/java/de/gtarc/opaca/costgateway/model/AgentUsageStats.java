package de.gtarc.opaca.costgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Per-agent usage snapshot returned by GET /gateway/stats and GET /gateway/stats/{agentId}.
 * Every field maps directly to a Redis key so dashboards and the admin API
 * read from a single source of truth.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentUsageStats {

    private String agentId;

    /** Tokens consumed in the current budget window */
    private long tokensUsed;

    /** Configured budget for this agent (defaults to gateway default) */
    private long tokenBudget;

    /** tokensUsed / tokenBudget × 100 */
    private double usagePercent;

    /** Running USD cost this window */
    private double totalCostUsd;

    /** Total successful invocations in this window */
    private long requestCount;

    /** Calls rejected with HTTP 429 in this window */
    private long throttledCount;

    /** Seconds until the budget window resets (Redis TTL) */
    private long budgetResetInSeconds;

    /**
     * Current routing tier based on usagePercent:
     *   PRIMARY  → < tier2Threshold %
     *   ECONOMY  → >= tier2Threshold % and < tier3Threshold %
     *   FALLBACK → >= tier3Threshold %
     */
    private String currentTier;
}
