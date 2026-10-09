package de.gtarc.opaca.costgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Immutable record of LLM token consumption for a single invocation.
 * Used to update Redis counters and Prometheus metrics after every call.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenUsage {

    /** Tokens in the user's prompt / system message */
    private long promptTokens;

    /** Tokens in the model's completion */
    private long completionTokens;

    /** promptTokens + completionTokens */
    private long totalTokens;

    /** Estimated USD cost for this call */
    private double estimatedCostUsd;

    /** Tier that served this call: PRIMARY | ECONOMY | FALLBACK */
    private String tier;

    /** Model identifier e.g. openai/gpt-oss-20b or gemini-3.8-flash */
    private String model;

    /** Wall-clock latency in milliseconds */
    private long latencyMs;
}
