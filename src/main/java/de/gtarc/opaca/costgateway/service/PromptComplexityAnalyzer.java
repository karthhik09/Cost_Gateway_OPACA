package de.gtarc.opaca.costgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.gtarc.opaca.costgateway.config.GatewayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Complexity-Aware Routing — Feature 1 (Conference Paper Novelty).
 *
 * <p>Analyses an incoming prompt's textual content <em>before</em> a routing
 * decision is made and classifies it as SIMPLE or COMPLEX.  Simple tasks are
 * fast-tracked directly to Tier 2 (Groq) regardless of budget usage, so that
 * the Tier-1 (OPACA) quota is preserved for reasoning-heavy tasks.
 *
 * <h3>Classification algorithm</h3>
 * <ol>
 *   <li><b>Keyword matching</b> — a curated set of "simple" action verbs
 *       (translate, summarise, define, …) and "complex" indicators (analyse,
 *       optimise, reason, plan, …) each contribute a weighted score.</li>
 *   <li><b>Prompt length</b> — prompts shorter than {@code SIMPLE_LENGTH_THRESHOLD}
 *       characters lean towards SIMPLE; prompts above {@code COMPLEX_LENGTH_THRESHOLD}
 *       lean towards COMPLEX.</li>
 *   <li><b>Parameter density</b> — many parameters suggest a structured,
 *       non-trivial task.</li>
 *   <li><b>Question depth</b> — multiple sentences or nested clauses raise the
 *       complexity score.</li>
 * </ol>
 *
 * The final score is normalised to [0, 1].  Any score below
 * {@code SIMPLE_SCORE_CUTOFF} is classified as SIMPLE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptComplexityAnalyzer {

    private final GatewayConfig config;

    // ── Tuneable constants ─────────────────────────────────────────────────────

    /** Prompts with fewer than this many characters are considered short. */
    private static final int SIMPLE_LENGTH_THRESHOLD  = 80;
    /** Prompts with more than this many characters are considered long. */
    private static final int COMPLEX_LENGTH_THRESHOLD = 300;
    /** Normalised scores below this cutoff → SIMPLE. */
    private static final double SIMPLE_SCORE_CUTOFF   = 0.35;
    /** Max parameter count before complexity score is fully saturated. */
    private static final int MAX_PARAM_SATURATION     = 8;

    // ── Keyword sets ───────────────────────────────────────────────────────────

    private static final Set<String> SIMPLE_KEYWORDS = Set.of(
            "translate", "convert", "define", "summarise", "summarize",
            "list", "show", "echo", "repeat", "format", "count",
            "lookup", "find", "get", "fetch", "retrieve", "ping",
            "hello", "greet", "date", "time", "spell", "word"
    );

    private static final Set<String> COMPLEX_KEYWORDS = Set.of(
            "analyse", "analyze", "optimise", "optimize", "reason",
            "infer", "predict", "explain", "compare", "evaluate",
            "plan", "strategy", "recommend", "decide", "classify",
            "generate", "design", "architect", "debug", "diagnose",
            "orchestrate", "assess", "review", "critique"
    );

    // Pre-compiled patterns for efficiency
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?]+\\s+");
    private static final Pattern CLAUSE_SPLIT   = Pattern.compile("[,;]+");

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the task is assessed as simple enough to
     * bypass Tier-1 and route directly to Tier-2 (Economy / Groq).
     *
     * @param action     the OPACA action name
     * @param parameters the request parameter map
     * @return true iff the task is classified as SIMPLE
     */
    public boolean isSimpleTask(String action, Map<String, JsonNode> parameters) {
        String combinedText = buildAnalysisText(action, parameters);
        double score = computeComplexityScore(combinedText, parameters);
        double cutoff = config.getRouting().getSimpleScoreCutoff();

        ComplexityClass cls = score < cutoff
                ? ComplexityClass.SIMPLE : ComplexityClass.COMPLEX;

        log.info("Complexity analysis: action='{}' params={} textLen={} score={} cutoff={} -> {}",
                action, parameters.size(), combinedText.length(),
                String.format("%.3f", score), String.format("%.3f", cutoff), cls);

        return cls == ComplexityClass.SIMPLE;
    }

    /**
     * Returns the raw normalised complexity score in [0, 1].
     * Useful for metrics / Grafana dashboards.
     */
    public double getComplexityScore(String action, Map<String, JsonNode> parameters) {
        return computeComplexityScore(buildAnalysisText(action, parameters), parameters);
    }

    // ── Internal scoring ───────────────────────────────────────────────────────

    private String buildAnalysisText(String action, Map<String, JsonNode> parameters) {
        StringBuilder sb = new StringBuilder(action.toLowerCase().replace("_", " "));
        parameters.forEach((k, v) -> {
            sb.append(" ").append(k.toLowerCase().replace("_", " "));
            sb.append(" ").append(v.asText().toLowerCase());
        });
        return sb.toString();
    }

    /**
     * Computes a weighted complexity score in the range [0, 1].
     *
     * Signal contributions:
     *   Simple keyword hits    -0.08 each (reduces score)
     *   Complex keyword hits   +0.12 each
     *   Text length            up to ±0.20
     *   Parameter density      up to +0.15
     *   Sentence / clause depth up to +0.15
     */
    private double computeComplexityScore(String text, Map<String, JsonNode> parameters) {
        double score = 0.5; // neutral prior

        // ── 1. Keyword signals ───────────────────────────────────────────────
        long simpleHits  = SIMPLE_KEYWORDS.stream().filter(text::contains).count();
        long complexHits = COMPLEX_KEYWORDS.stream().filter(text::contains).count();

        score -= simpleHits  * 0.08;
        score += complexHits * 0.12;

        // ── 2. Text length signal ────────────────────────────────────────────
        int len = text.length();
        if (len < SIMPLE_LENGTH_THRESHOLD) {
            score -= 0.10;
        } else if (len > COMPLEX_LENGTH_THRESHOLD) {
            double lenFactor = Math.min(1.0, (double)(len - COMPLEX_LENGTH_THRESHOLD) / 500.0);
            score += 0.20 * lenFactor;
        }

        // ── 3. Parameter density ─────────────────────────────────────────────
        int paramCount = parameters.size();
        double paramFactor = Math.min(1.0, (double) paramCount / MAX_PARAM_SATURATION);
        score += 0.15 * paramFactor;

        // ── 4. Sentence / clause depth ───────────────────────────────────────
        int sentences = SENTENCE_SPLIT.split(text).length;
        int clauses   = CLAUSE_SPLIT.split(text).length;
        double depthFactor = Math.min(1.0, (sentences + clauses - 2) / 10.0);
        score += 0.15 * Math.max(0.0, depthFactor);

        // Clamp to [0, 1]
        return Math.max(0.0, Math.min(1.0, score));
    }

    // ── Internal enum ──────────────────────────────────────────────────────────

    private enum ComplexityClass { SIMPLE, COMPLEX }
}
