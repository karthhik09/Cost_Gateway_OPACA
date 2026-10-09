package de.gtarc.opaca.costgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * Unified response wrapper returned by POST /invoke on the gateway.
 * On success: result + tokenUsage are populated.
 * On error:   error + errorCode are populated and HTTP status is non-2xx.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayResponse {

    /** The result payload returned from the upstream (OPACA, Groq, or Gemini) */
    private String result;

    /** Raw JSON result node (used when the upstream returns structured JSON) */
    private JsonNode resultNode;

    /** Token accounting for this invocation */
    private TokenUsage tokenUsage;

    /** Routing tier that served this request: PRIMARY | ECONOMY | FALLBACK | REJECTED */
    private String tier;

    /** Model identifier */
    private String model;

    /** Agent ID this call was for */
    private String agentId;

    // ── Error fields (non-null only on failure) ──────────────────────────────

    /** Human-readable error message */
    private String error;

    /** Machine-readable error code */
    private String errorCode;

    /** HTTP status code of the upstream error (if any) */
    private Integer upstreamStatus;
}
