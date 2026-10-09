package de.gtarc.opaca.costgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Strongly-typed configuration loaded from application.properties / environment variables.
 *
 * All nested groups map to gateway.* namespace.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {

    private OpacaConfig opaca = new OpacaConfig();
    private BudgetConfig budget = new BudgetConfig();
    private RoutingConfig routing = new RoutingConfig();
    private GroqConfig groq = new GroqConfig();
    private GeminiConfig gemini = new GeminiConfig();

    // ── OPACA upstream ────────────────────────────────────────────────────────

    @Data
    public static class OpacaConfig {
        /** Base URL of the OPACA runtime platform */
        private String baseUrl = "http://localhost:8000";
        /** HTTP timeout for OPACA calls in seconds */
        private int timeoutSeconds = 30;
    }

    // ── Token budget ──────────────────────────────────────────────────────────

    @Data
    public static class BudgetConfig {
        /** Default token budget per agent per window */
        private long defaultTokenBudget = 10000;
        /** Window duration in seconds (default: 1 hour) */
        private long resetWindowSeconds = 3600;
        /** Cost per 1,000 prompt tokens in USD */
        private double costPer1kPromptTokens = 0.0005;
        /** Cost per 1,000 completion tokens in USD */
        private double costPer1kCompletionTokens = 0.0015;
    }

    // ── Tier routing ──────────────────────────────────────────────────────────

    @Data
    public static class RoutingConfig {
        /**
         * When budget usage exceeds this %, route to Tier 2 (ECONOMY / Groq).
         * Default: 80
         */
        private int tier2ThresholdPercent = 80;
        /**
         * When budget usage exceeds this %, route to Tier 3 (FALLBACK / Gemini).
         * Default: 100
         */
        private int tier3ThresholdPercent = 100;
    }

    // ── Groq (Tier 2 — Economy LLM) ───────────────────────────────────────────

    @Data
    public static class GroqConfig {
        private String apiKey = "";
        private String baseUrl = "https://api.groq.com/openai/v1";
        private String model = "openai/gpt-oss-20b";
        private int timeoutSeconds = 30;
    }

    // ── Gemini (Tier 3 — Fallback LLM) ───────────────────────────────────────

    @Data
    public static class GeminiConfig {
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String model = "gemini-3.8-flash";
        private int timeoutSeconds = 30;
    }
}
