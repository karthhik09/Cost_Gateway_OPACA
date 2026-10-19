package de.gtarc.opaca.costgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.gtarc.opaca.costgateway.config.GatewayConfig;
import de.gtarc.opaca.costgateway.model.GatewayResponse;
import de.gtarc.opaca.costgateway.model.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Implements the three-tier model routing strategy for OPACA agent invocations.
 *
 * <pre>
 *  Tier 1 — PRIMARY   usage &lt; tier2Threshold%  → OPACA platform (full capability)
 *  Tier 2 — ECONOMY   tier2Threshold% ≤ usage &lt; tier3Threshold% → Groq LLM (cheap)
 *  Tier 3 — FALLBACK  usage ≥ tier3Threshold%  → Gemini LLM (ultra-cheap)
 * </pre>
 *
 * Prometheus timer metrics are emitted per call so Grafana can show
 * latency percentiles (p50/p95/p99) broken down by tier.
 */
@Slf4j
@Service
public class ModelRoutingService {

    static final String TIER_PRIMARY  = "PRIMARY";
    static final String TIER_ECONOMY  = "ECONOMY";
    static final String TIER_FALLBACK = "FALLBACK";

    private final GatewayConfig config;
    private final TokenTrackingService tracker;
    private final PromptComplexityAnalyzer complexityAnalyzer;
    private final WebClient opacaClient;
    private final WebClient groqClient;
    private final WebClient geminiClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public ModelRoutingService(
            GatewayConfig config,
            TokenTrackingService tracker,
            PromptComplexityAnalyzer complexityAnalyzer,
            @Qualifier("opacaClient")  WebClient opacaClient,
            @Qualifier("groqClient")   WebClient groqClient,
            @Qualifier("geminiClient") WebClient geminiClient,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this.config             = config;
        this.tracker            = tracker;
        this.complexityAnalyzer = complexityAnalyzer;
        this.opacaClient        = opacaClient;
        this.groqClient         = groqClient;
        this.geminiClient       = geminiClient;
        this.objectMapper       = objectMapper;
        this.meters             = meters;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public GatewayResponse routeInvoke(
            String action,
            Map<String, JsonNode> parameters,
            String agentId,
            int timeout,
            double usagePctBeforeBorrow) {

        String eid = agentId != null ? agentId : "default";
        // Use the pre-borrow usage% so routing decisions reflect the agent's REAL
        // financial state before Dynamic Budget Sharing extended its budget.
        // Without this, borrowing would always reset pct to <100%, making FALLBACK unreachable.
        double pct = usagePctBeforeBorrow;
        int t2     = config.getRouting().getTier2ThresholdPercent();  // default 80
        int t3     = config.getRouting().getTier3ThresholdPercent();  // default 100

        // ── Complexity-Aware Routing (Feature 1) ────────────────────────────
        boolean simpleTask     = complexityAnalyzer.isSimpleTask(action, parameters);
        double complexityScore = complexityAnalyzer.getComplexityScore(action, parameters);

        // Emit routing decision counter for Prometheus
        Counter.builder("gateway_simple_task_routing_total")
                .description("Requests fast-tracked to Tier 2 due to low complexity")
                .tag("agent", eid)
                .tag("simple", String.valueOf(simpleTask))
                .register(meters)
                .increment();

        // ── Budget-based tier selection (priority order matters) ──────────────
        // Rule 1: Budget >= 100% (Tier-3 threshold) → ALWAYS Gemini (FALLBACK)
        //         This takes priority over everything including complexity.
        // Rule 2: Budget >= 80% (Tier-2 threshold) → ECONOMY, regardless of complexity
        //         Budget pressure overrides the complexity fast-track.
        // Rule 3: Simple task + healthy budget → ECONOMY (complexity fast-track saves Tier-1)
        // Rule 4: Complex task + healthy budget → PRIMARY (full OPACA)
        String tier;
        if (pct >= t3) {
            tier = TIER_FALLBACK;
            log.info("Budget exhausted ({}% >= {}%) → FALLBACK (Gemini)", String.format("%.1f", pct), t3);
        } else if (pct >= t2) {
            tier = TIER_ECONOMY;
            log.info("Budget pressure ({}% >= {}%) → ECONOMY (Groq)", String.format("%.1f", pct), t2);
        } else if (simpleTask) {
            // Healthy budget but simple task → Economy to preserve Tier-1 for complex work
            tier = TIER_ECONOMY;
            log.info("CAR: simple task (score={}) fast-tracked → ECONOMY (budget={}%)",
                    String.format("%.3f", complexityScore), String.format("%.1f", pct));
        } else {
            // Complex task + healthy budget → full OPACA (PRIMARY)
            tier = TIER_PRIMARY;
            log.info("Complex task (score={}) + healthy budget ({}%) → PRIMARY (OPACA)",
                    String.format("%.3f", complexityScore), String.format("%.1f", pct));
        }

        log.info("Route invoke/{} agent={} usage={}% complexity={} simple={} → {}",
                action, eid, String.format("%.1f", pct), String.format("%.3f", complexityScore), simpleTask, tier);

        return switch (tier) {
            case TIER_PRIMARY  -> {
                try {
                    yield timedCall(TIER_PRIMARY,  eid, () -> callOpaca(action, parameters, agentId, timeout));
                } catch (Exception e) {
                    log.warn("OPACA platform on port 8000 unreachable ({}). Seamlessly falling back to Tier 2 (Groq)", e.getMessage());
                    yield timedCall(TIER_ECONOMY, eid, () -> callGroq(action, parameters, eid));
                }
            }
            case TIER_ECONOMY  -> timedCall(TIER_ECONOMY,  eid, () -> callGroq(action, parameters, eid));
            default            -> timedCall(TIER_FALLBACK, eid, () -> callGemini(action, parameters, eid));
        };
    }

    // ── Tier 1: OPACA ─────────────────────────────────────────────────────────

    private GatewayResponse callOpaca(String action, Map<String, JsonNode> params,
                                    String agentId, int timeout) {
        String path = (agentId != null)
                ? "/invoke/" + action + "/" + agentId
                : "/invoke/" + action;
        String query = timeout > 0 ? "?timeout=" + timeout : "";
        log.debug("→ OPACA {}{}", path, query);

        String body = opacaClient.post()
                .uri(path + query)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(params)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getOpaca().getTimeoutSeconds()))
                .block();

        JsonNode node = null;
        String text = body;
        try { node = objectMapper.readTree(body); text = node.isTextual() ? node.asText() : body; }
        catch (Exception ignored) {}

        return GatewayResponse.builder()
                .result(text).resultNode(node)
                .tier(TIER_PRIMARY).model("opaca-platform")
                .tokenUsage(TokenUsage.builder()
                        .promptTokens(0).completionTokens(0).totalTokens(0)
                        .estimatedCostUsd(0.0).tier(TIER_PRIMARY).model("opaca-platform").build())
                .build();
    }

    // ── Tier 2: Groq ──────────────────────────────────────────────────────────

    private GatewayResponse callGroq(String action, Map<String, JsonNode> params, String agentId) {
        String model = config.getGroq().getModel();
        String prompt = buildPrompt(action, params, agentId);
        log.debug("→ Groq model={}", model);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 1024);
        body.withArray("messages").addObject().put("role", "user").put("content", prompt);

        String resp = groqClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getGroq().getApiKey())
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getGroq().getTimeoutSeconds()))
                .block();

        return GatewayResponse.builder()
                .result(extractOpenAiContent(resp))
                .tier(TIER_ECONOMY).model(model)
                .tokenUsage(parseOpenAiTokens(resp, TIER_ECONOMY, model))
                .build();
    }

    // ── Tier 3: Gemini ────────────────────────────────────────────────────────

    private GatewayResponse callGemini(String action, Map<String, JsonNode> params, String agentId) {
        String model  = config.getGemini().getModel();
        String prompt = buildPrompt(action, params, agentId);
        log.debug("→ Gemini model={}", model);

        ObjectNode body = objectMapper.createObjectNode();
        body.withArray("contents").addObject().withArray("parts").addObject().put("text", prompt);

        String resp = geminiClient.post()
                .uri("/models/" + model + ":generateContent?key=" + config.getGemini().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(errBody -> new RuntimeException(
                                        "Gemini API error " + clientResponse.statusCode() + ": " + errBody)))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getGemini().getTimeoutSeconds()))
                .block();

        return GatewayResponse.builder()
                .result(extractGeminiContent(resp))
                .tier(TIER_FALLBACK).model(model)
                .tokenUsage(parseGeminiTokens(resp, model))
                .build();
    }

    // ── Timer wrapper ─────────────────────────────────────────────────────────

    @FunctionalInterface interface TierCall { GatewayResponse call() throws Exception; }

    private GatewayResponse timedCall(String tier, String agentId, TierCall fn) {
        Timer timer = Timer.builder("gateway_invoke_duration_seconds")
                .description("Per-tier invocation latency")
                .tag("tier", tier).tag("agent", agentId)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        long t0 = System.currentTimeMillis();
        try {
            GatewayResponse r = timer.recordCallable(fn::call);
            if (r != null && r.getTokenUsage() != null)
                r.getTokenUsage().setLatencyMs(System.currentTimeMillis() - t0);
            return r;
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Upstream " + tier + " HTTP " + e.getStatusCode()
                    + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("Tier " + tier + " failed: " + e.getMessage(), e);
        }
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildPrompt(String action, Map<String, JsonNode> params, String agentId) {
        StringBuilder sb = new StringBuilder("You are an AI assistant in a multi-agent system.\n");
        sb.append("Action: '").append(action).append("'");
        if (agentId != null) sb.append(", Agent: '").append(agentId).append("'");
        sb.append("\nParameters:\n");
        params.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v.asText()).append("\n"));
        sb.append("Respond helpfully and concisely.");
        return sb.toString();
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private String extractOpenAiContent(String body) {
        try {
            JsonNode choices = objectMapper.readTree(body).get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty())
                return choices.get(0).path("message").path("content").asText("");
        } catch (Exception e) { log.warn("Groq content parse error: {}", e.getMessage()); }
        return body;
    }

    private TokenUsage parseOpenAiTokens(String body, String tier, String model) {
        try {
            JsonNode u = objectMapper.readTree(body).get("usage");
            if (u != null) return buildUsage(u.path("prompt_tokens").asLong(),
                    u.path("completion_tokens").asLong(), tier, model);
        } catch (Exception e) { log.warn("Groq token parse error: {}", e.getMessage()); }
        return buildUsage(0, 0, tier, model);
    }

    private String extractGeminiContent(String body) {
        try {
            JsonNode parts = objectMapper.readTree(body).path("candidates").get(0)
                    .path("content").path("parts");
            if (parts != null && parts.isArray() && !parts.isEmpty())
                return parts.get(0).path("text").asText("");
        } catch (Exception e) { log.warn("Gemini content parse error: {}", e.getMessage()); }
        return body;
    }

    private TokenUsage parseGeminiTokens(String body, String model) {
        try {
            JsonNode u = objectMapper.readTree(body).get("usageMetadata");
            if (u != null) return buildUsage(u.path("promptTokenCount").asLong(),
                    u.path("candidatesTokenCount").asLong(), TIER_FALLBACK, model);
        } catch (Exception e) { log.warn("Gemini token parse error: {}", e.getMessage()); }
        return buildUsage(0, 0, TIER_FALLBACK, model);
    }

    private TokenUsage buildUsage(long prompt, long completion, String tier, String model) {
        long total  = prompt + completion;
        double cost = (prompt / 1000.0) * config.getBudget().getCostPer1kPromptTokens()
                    + (completion / 1000.0) * config.getBudget().getCostPer1kCompletionTokens();
        return TokenUsage.builder()
                .promptTokens(prompt).completionTokens(completion).totalTokens(total)
                .estimatedCostUsd(cost).tier(tier).model(model).build();
    }
}
