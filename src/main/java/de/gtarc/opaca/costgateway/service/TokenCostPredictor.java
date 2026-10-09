package de.gtarc.opaca.costgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.gtarc.opaca.costgateway.config.GatewayConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Predictive Token Costing — Feature 3 (Conference Paper Novelty).
 *
 * <p>Estimates the expected token cost of a request <em>before</em> making
 * the API call, and pre-emptively rejects it if the predicted cost would
 * exceed the agent's remaining budget.  This prevents partial budget bleed
 * from requests that are guaranteed to fail the budget check after the fact.
 *
 * <h3>Estimation methodology</h3>
 * <ol>
 *   <li><b>Prompt token estimate</b> — a lightweight heuristic based on
 *       OpenAI's empirical rule of ≈ 4 characters per token, applied to
 *       the serialised prompt text constructed from the action and parameters.
 *       A conservative safety multiplier ({@code SAFETY_FACTOR}) is applied
 *       to account for system message overhead.</li>
 *   <li><b>Completion token estimate</b> — models produce roughly 0.6× the
 *       number of tokens they receive in the prompt on average; this ratio
 *       is configurable via {@code COMPLETION_RATIO}.</li>
 *   <li><b>Total estimate</b> — promptEst + completionEst.</li>
 * </ol>
 *
 * <h3>Pre-emptive rejection</h3>
 * If {@code predictedTokens > remainingBudget}, the request is rejected
 * <em>before</em> any upstream API call is made, saving both money and
 * latency.  The predicted cost is also surfaced in the rejection response
 * body so clients can adapt their request size.
 *
 * <h3>Metrics emitted</h3>
 * {@code gateway_predicted_tokens_summary} (histogram),
 * {@code gateway_preemptive_rejections_total}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCostPredictor {

    // ── Tuneable constants ─────────────────────────────────────────────────────

    /**
     * Average number of UTF-8 characters per token (OpenAI empirical figure).
     * We use 3.5 instead of 4 to be conservative (i.e., overestimate).
     */
    private static final double CHARS_PER_TOKEN = 3.5;

    /**
     * Safety multiplier applied on top of the character-based estimate to
     * account for system message overhead, special tokens, and encoding
     * differences across models.
     */
    private static final double SAFETY_FACTOR = 1.25;

    /**
     * Expected completion-to-prompt token ratio. Empirically, models produce
     * roughly 0.6× the prompt tokens in the completion for typical tasks.
     */
    private static final double COMPLETION_RATIO = 0.60;

    /**
     * Minimum predicted tokens floor — even a one-word request incurs
     * system prompt overhead.
     */
    private static final long MIN_PREDICTED_TOKENS = 50L;

    private final GatewayConfig config;
    private final MeterRegistry meters;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Predicts the token cost of the given request.
     *
     * @param action     the OPACA action name
     * @param parameters the request parameter map
     * @return estimated total tokens (prompt + completion)
     */
    public long predictTokens(String action, Map<String, JsonNode> parameters) {
        String promptText   = buildPromptText(action, parameters);
        double charsPerToken = config.getPredictor().getCharsPerToken();
        double safetyFactor  = config.getPredictor().getSafetyFactor();
        long promptEst       = (long)((promptText.length() / charsPerToken) * safetyFactor);
        long completionEst   = (long)(promptEst * COMPLETION_RATIO);
        long totalEst        = Math.max(MIN_PREDICTED_TOKENS, promptEst + completionEst);

        log.debug("Token prediction: action='{}' chars={} promptEst={} completionEst={} total={}",
                action, promptText.length(), promptEst, completionEst, totalEst);

        // Record prediction distribution for Grafana histogram
        DistributionSummary.builder("gateway_predicted_tokens_summary")
                .description("Distribution of predicted token costs per request")
                .baseUnit("tokens")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)
                .record(totalEst);

        return totalEst;
    }

    /**
     * Determines whether this request should be pre-emptively rejected.
     *
     * <p>The request is rejected if its predicted token cost exceeds the
     * agent's remaining budget.  A small {@code headroomFraction} (10 %) is
     * reserved to allow for estimation error without hard-blocking the agent.
     *
     * @param agentId        the agent making the request
     * @param tokensUsed     tokens already consumed this window
     * @param tokenBudget    the agent's total token budget
     * @param action         the OPACA action name
     * @param parameters     the request parameter map
     * @return a {@link PredictionResult} describing the decision
     */
    public PredictionResult shouldReject(
            String agentId,
            long tokensUsed,
            long tokenBudget,
            String action,
            Map<String, JsonNode> parameters) {

        long predicted  = predictTokens(action, parameters);
        long remaining  = Math.max(0, tokenBudget - tokensUsed);

        // Apply configurable headroom: only reject if predicted > (1 - headroom) * remaining
        double headroom          = config.getPredictor().getHeadroomFraction();
        long effectiveRemaining  = (long)(remaining * (1.0 - headroom));
        boolean shouldReject     = predicted > effectiveRemaining && remaining > 0;

        if (shouldReject) {
            log.warn("Pre-emptive reject: agent={} predicted={} remaining={} (effective={})",
                    agentId, predicted, remaining, effectiveRemaining);

            Counter.builder("gateway_preemptive_rejections_total")
                    .description("Requests rejected preemptively due to predicted token overflow")
                    .tag("agent", agentId)
                    .register(meters)
                    .increment();
        } else {
            log.debug("Pre-emptive check passed: agent={} predicted={} remaining={}",
                    agentId, predicted, remaining);
        }

        double predictedCostUsd = estimateCostUsd(predicted);

        return new PredictionResult(predicted, remaining, predictedCostUsd, shouldReject);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private String buildPromptText(String action, Map<String, JsonNode> parameters) {
        // Mirror the prompt structure used in ModelRoutingService.buildPrompt()
        // so the estimate is as accurate as possible.
        StringBuilder sb = new StringBuilder("You are an AI assistant in a multi-agent system.\n");
        sb.append("Action: '").append(action).append("'\n");
        sb.append("Parameters:\n");
        parameters.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v.asText()).append("\n"));
        sb.append("Respond helpfully and concisely.");
        return sb.toString();
    }

    private long estimatePromptTokens(String promptText) {
        long charBased = (long)(promptText.length() / CHARS_PER_TOKEN);
        return (long)(charBased * SAFETY_FACTOR);
    }

    private double estimateCostUsd(long tokens) {
        // Assume prompt/completion split by COMPLETION_RATIO
        double promptTokens     = tokens / (1.0 + COMPLETION_RATIO);
        double completionTokens = tokens - promptTokens;
        return (promptTokens / 1000.0) * config.getBudget().getCostPer1kPromptTokens()
             + (completionTokens / 1000.0) * config.getBudget().getCostPer1kCompletionTokens();
    }

    // ── Result record ──────────────────────────────────────────────────────────

    /**
     * Immutable result of a pre-emptive cost prediction check.
     */
    public static final class PredictionResult {

        /** Estimated total tokens this request will consume. */
        public final long predictedTokens;

        /** Tokens remaining in the agent's budget before this request. */
        public final long remainingBudget;

        /** Estimated USD cost for the predicted token count. */
        public final double predictedCostUsd;

        /**
         * {@code true} if the request should be rejected before calling any
         * upstream API.
         */
        public final boolean shouldReject;

        public PredictionResult(long predictedTokens, long remainingBudget,
                                double predictedCostUsd, boolean shouldReject) {
            this.predictedTokens  = predictedTokens;
            this.remainingBudget  = remainingBudget;
            this.predictedCostUsd = predictedCostUsd;
            this.shouldReject     = shouldReject;
        }
    }
}
