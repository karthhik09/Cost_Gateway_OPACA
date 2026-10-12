package de.gtarc.opaca.costgateway.service;

import de.gtarc.opaca.costgateway.config.GatewayConfig;
import de.gtarc.opaca.costgateway.model.AgentUsageStats;
import de.gtarc.opaca.costgateway.model.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracks per-agent LLM token consumption in Redis.
 *
 * <h3>Redis Key Schema</h3>
 * <pre>
 *   agent:{id}:tokens:used       Long  — rolling token counter (TTL = window)
 *   agent:{id}:tokens:budget     Long  — configurable per-agent limit
 *   agent:{id}:cost:usd          String — running USD cost this window
 *   agent:{id}:requests:total    Long  — successful invocations this window
 *   agent:{id}:requests:throttled Long — 429 rejections this window
 * </pre>
 *
 * All keys for a given agent share the same TTL so the window resets atomically.
 * The token counter uses Redis INCRBY which is atomic — safe under concurrent load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenTrackingService {

    // Key templates
    static final String KEY_TOKENS_USED      = "agent:%s:tokens:used";
    static final String KEY_TOKENS_BUDGET    = "agent:%s:tokens:budget";
    static final String KEY_COST_USD         = "agent:%s:cost:usd";
    static final String KEY_REQ_TOTAL        = "agent:%s:requests:total";
    static final String KEY_REQ_THROTTLED    = "agent:%s:requests:throttled";

    private final StringRedisTemplate redis;
    private final GatewayConfig config;
    private final MeterRegistry meters;

    // ── Write operations ───────────────────────────────────────────────────────

    /**
     * Record token usage after a successful invocation.
     * Increments all counters and applies the budget window TTL on first use.
     */
    public void recordUsage(String agentId, TokenUsage usage) {
        long tokens = usage.getTotalTokens();
        long windowSec = config.getBudget().getResetWindowSeconds();

        String tokensKey  = key(KEY_TOKENS_USED, agentId);
        String costKey    = key(KEY_COST_USD, agentId);
        String reqKey     = key(KEY_REQ_TOTAL, agentId);

        // Atomic increment — safe for concurrent agents
        Long newTotal = redis.opsForValue().increment(tokensKey, tokens);
        redis.opsForValue().increment(reqKey);
        applyWindowTtlIfAbsent(tokensKey, reqKey, windowSec);

        // Accumulate cost (non-atomic but single-threaded enough for accounting)
        double prev = parseDouble(redis.opsForValue().get(costKey));
        redis.opsForValue().set(costKey, String.format("%.8f", prev + usage.getEstimatedCostUsd()));
        applyWindowTtlIfAbsent(costKey, windowSec);

        log.info("Agent [{}] +{} tokens (window total: {}) tier={} model={}",
                agentId, tokens, newTotal, usage.getTier(), usage.getModel());

        // Prometheus counters (these accumulate forever — no TTL)
        Counter.builder("gateway_tokens_used_total")
                .description("Total LLM tokens consumed")
                .tag("agent", agentId)
                .tag("tier", nullSafe(usage.getTier()))
                .register(meters)
                .increment(tokens);

        Counter.builder("gateway_requests_total")
                .description("Total invocations forwarded")
                .tag("agent", agentId)
                .tag("tier", nullSafe(usage.getTier()))
                .tag("model", nullSafe(usage.getModel()))
                .register(meters)
                .increment();

        Counter.builder("gateway_cost_usd_total")
                .description("Total estimated LLM cost in USD")
                .tag("agent", agentId)
                .tag("tier", nullSafe(usage.getTier()))
                .register(meters)
                .increment(usage.getEstimatedCostUsd());

        // Tier distribution (for pie/bar chart in Grafana)
        Counter.builder("gateway_tier_distribution_total")
                .description("Invocations served by each tier")
                .tag("agent", agentId)
                .tag("tier", nullSafe(usage.getTier()))
                .register(meters)
                .increment();

        // Register a gauge that tracks current window usage % (for alerting)
        final String aid = agentId;
        Gauge.builder("gateway_budget_usage_percent",
                        () -> getBudgetUsagePercent(aid))
                .description("Current budget usage percentage for this agent")
                .tag("agent", agentId)
                .register(meters);
    }

    /**
     * Record a 429 throttle event (budget exceeded rejection).
     */
    public void recordThrottle(String agentId) {
        String throttledKey = key(KEY_REQ_THROTTLED, agentId);
        redis.opsForValue().increment(throttledKey);
        applyWindowTtlIfAbsent(throttledKey, config.getBudget().getResetWindowSeconds());

        Counter.builder("gateway_throttle_events_total")
                .description("Requests rejected due to token budget exhaustion (HTTP 429)")
                .tag("agent", agentId)
                .register(meters)
                .increment();

        log.warn("Agent [{}]: 429 budget throttle recorded", agentId);
    }

    // ── Read operations ────────────────────────────────────────────────────────

    public long getTokensUsed(String agentId) {
        return parseLong(redis.opsForValue().get(key(KEY_TOKENS_USED, agentId)));
    }

    public long getTokenBudget(String agentId) {
        String v = redis.opsForValue().get(key(KEY_TOKENS_BUDGET, agentId));
        return v != null ? parseLong(v) : config.getBudget().getDefaultTokenBudget();
    }

    public void setTokenBudget(String agentId, long budget) {
        redis.opsForValue().set(key(KEY_TOKENS_BUDGET, agentId), String.valueOf(budget));
        log.info("Agent [{}]: budget set to {} tokens", agentId, budget);
    }

    public void setTokensUsed(String agentId, long used) {
        String tokensKey = key(KEY_TOKENS_USED, agentId);
        redis.opsForValue().set(tokensKey, String.valueOf(used));
        applyWindowTtlIfAbsent(tokensKey, config.getBudget().getResetWindowSeconds());
        log.info("Agent [{}]: tokens used manually set to {}", agentId, used);
    }

    public double getBudgetUsagePercent(String agentId) {
        long used   = getTokensUsed(agentId);
        long budget = getTokenBudget(agentId);
        return budget == 0 ? 0.0 : (used * 100.0 / budget);
    }

    public boolean isBudgetExceeded(String agentId) {
        return getBudgetUsagePercent(agentId) >= config.getRouting().getTier3ThresholdPercent();
    }

    public long getBudgetResetInSeconds(String agentId) {
        Long ttl = redis.getExpire(key(KEY_TOKENS_USED, agentId), TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : config.getBudget().getResetWindowSeconds();
    }

    public void resetUsage(String agentId) {
        redis.delete(key(KEY_TOKENS_USED, agentId));
        redis.delete(key(KEY_COST_USD, agentId));
        redis.delete(key(KEY_REQ_TOTAL, agentId));
        redis.delete(key(KEY_REQ_THROTTLED, agentId));
        log.info("Agent [{}]: usage counters reset", agentId);
    }

    /**
     * Returns stats for a specific agent.
     */
    public AgentUsageStats getAgentStats(String agentId) {
        long used     = getTokensUsed(agentId);
        long budget   = getTokenBudget(agentId);
        double pct    = budget == 0 ? 0.0 : (used * 100.0 / budget);
        int t2 = config.getRouting().getTier2ThresholdPercent();
        int t3 = config.getRouting().getTier3ThresholdPercent();

        String tier = pct >= t3 ? "FALLBACK" : pct >= t2 ? "ECONOMY" : "PRIMARY";

        return AgentUsageStats.builder()
                .agentId(agentId)
                .tokensUsed(used)
                .tokenBudget(budget)
                .usagePercent(Math.round(pct * 10.0) / 10.0)
                .totalCostUsd(parseDouble(redis.opsForValue().get(key(KEY_COST_USD, agentId))))
                .requestCount(parseLong(redis.opsForValue().get(key(KEY_REQ_TOTAL, agentId))))
                .throttledCount(parseLong(redis.opsForValue().get(key(KEY_REQ_THROTTLED, agentId))))
                .budgetResetInSeconds(getBudgetResetInSeconds(agentId))
                .currentTier(tier)
                .build();
    }

    /**
     * Returns stats for all agents that have recorded usage in Redis this window.
     */
    public List<AgentUsageStats> getAllAgentStats() {
        Set<String> keys = redis.keys("agent:*:tokens:used");
        if (keys == null || keys.isEmpty()) return Collections.emptyList();

        return keys.stream()
                .map(k -> extractAgentId(k))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(this::getAgentStats)
                .collect(Collectors.toList());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String key(String template, String agentId) {
        return String.format(template, agentId);
    }

    private void applyWindowTtlIfAbsent(String key, long windowSec) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            redis.expire(key, windowSec, TimeUnit.SECONDS);
        }
    }

    private void applyWindowTtlIfAbsent(String key1, String key2, long windowSec) {
        applyWindowTtlIfAbsent(key1, windowSec);
        applyWindowTtlIfAbsent(key2, windowSec);
    }

    /** Extract agentId from key "agent:{agentId}:tokens:used" */
    private String extractAgentId(String key) {
        int first = key.indexOf(':');
        int second = key.indexOf(':', first + 1);
        if (first < 0 || second <= first) return null;
        return key.substring(first + 1, second);
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0L;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    private double parseDouble(String v) {
        if (v == null || v.isBlank()) return 0.0;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0.0; }
    }

    private String nullSafe(String s) {
        return s != null ? s : "unknown";
    }
}
