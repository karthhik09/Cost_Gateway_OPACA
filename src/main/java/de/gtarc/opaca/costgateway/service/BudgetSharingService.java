package de.gtarc.opaca.costgateway.service;

import de.gtarc.opaca.costgateway.config.GatewayConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Dynamic Budget Sharing — Feature 2 (Conference Paper Novelty).
 *
 * <p>Implements a <em>global token pool</em> from which budget-starved agents
 * can borrow tokens, and to which idle agents can donate excess budget.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>A Redis key {@code global:pool:tokens} stores the shared pool size.</li>
 *   <li>When agent-A's budget usage drops below {@code DONATE_THRESHOLD_PCT}
 *       and its remaining tokens exceed {@code MIN_DONATE_TOKENS}, it donates
 *       a configurable fraction of its surplus to the pool.</li>
 *   <li>When agent-B receives a 429 from an upstream LLM (or approaches the
 *       hard-reject threshold) it attempts to borrow from the pool.</li>
 *   <li>Borrowing is atomic (Redis DECRBY with a floor guard) to prevent
 *       over-drafting.</li>
 *   <li>The pool is seeded once per budget window by a configurable initial
 *       reserve ({@code INITIAL_POOL_TOKENS}).</li>
 * </ul>
 *
 * <h3>Metrics emitted</h3>
 * {@code gateway_pool_donated_tokens_total},
 * {@code gateway_pool_borrowed_tokens_total},
 * {@code gateway_pool_borrow_failures_total}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetSharingService {

    // Redis key for the global pool
    static final String GLOBAL_POOL_KEY        = "global:pool:tokens";
    static final String GLOBAL_POOL_WINDOW_KEY = "global:pool:window";

    /** Set once per budget-window per agent to cap donations to exactly one. */
    private static final String KEY_DONATED_WINDOW  = "agent:%s:pool:donated_window";
    /** Set once per budget-window per agent when it successfully borrows. */
    private static final String KEY_BORROWED_WINDOW = "agent:%s:pool:borrowed_window";

    /**
     * Pool is considered "low" when it falls below this fraction of its
     * initial seed. Donations are suppressed when the pool is already healthy
     * to prevent unbounded pool growth. (0.5 = donate only if pool < 50% of seed)
     */
    private static final double POOL_LOW_THRESHOLD_FRACTION = 0.5;

    /**
     * An agent donates when its usage is below this % of its own budget.
     * (Idle agent = using less than half its budget)
     */
    private static final double DONATE_THRESHOLD_PCT = 50.0;

    /**
     * Minimum tokens an agent must donate per event to avoid micro-transfers.
     */
    private static final long MIN_DONATE_TOKENS = 200L;

    /**
     * Fraction of the agent's remaining budget donated to the pool (20 %).
     */
    private static final double DONATE_FRACTION = 0.20;

    /**
     * Maximum tokens a single agent can borrow in one operation.
     */
    private static final long MAX_BORROW_TOKENS = 2000L;

    /**
     * Initial pool size seeded at the start of each budget window.
     * This represents a small shared reserve for cross-agent lending.
     */
    private static final long INITIAL_POOL_TOKENS = 3000L;

    private final StringRedisTemplate redis;
    private final GatewayConfig config;
    private final MeterRegistry meters;

    // ── Pool seeding ───────────────────────────────────────────────────────────

    /**
     * Ensures the global pool is initialised for the current budget window.
     * Called lazily on first access; subsequent calls within the window are no-ops.
     */
    public void ensurePoolInitialised() {
        long initialTokens = config.getPool().getInitialTokens();
        Boolean set = redis.opsForValue().setIfAbsent(
                GLOBAL_POOL_KEY,
                String.valueOf(initialTokens));
        if (Boolean.TRUE.equals(set)) {
            long windowSec = config.getBudget().getResetWindowSeconds();
            redis.expire(GLOBAL_POOL_KEY, windowSec, TimeUnit.SECONDS);
            redis.expire(GLOBAL_POOL_WINDOW_KEY, windowSec, TimeUnit.SECONDS);
            log.info("Global pool seeded with {} tokens (window={}s)", initialTokens, windowSec);
        }
    }

    // ── Donation ───────────────────────────────────────────────────────────────

    /**
     * Called after recording usage for an agent that is well within its budget.
     * Guards ensure the pool stays well-behaved:
     * <ol>
     *   <li><b>Once per window</b> — a Redis flag prevents repeated donations
     *       within the same budget window, stopping budget drain on every call.</li>
     *   <li><b>Pool already healthy</b> — donation is skipped if the pool is
     *       already above {@code POOL_LOW_THRESHOLD_FRACTION * initialTokens},
     *       preventing unbounded pool growth.</li>
     *   <li><b>No borrowers donate</b> — an agent that borrowed this window
     *       never donates back in the same window (prevents Borrower→Pool ping-pong).</li>
     * </ol>
     *
     * @param agentId      the donating agent
     * @param tokensUsed   tokens used so far this window
     * @param tokenBudget  the agent's total budget
     */
    public void tryDonate(String agentId, long tokensUsed, long tokenBudget) {
        if (tokenBudget == 0) return;

        // Guard 1: only donate once per budget window
        String donatedKey = String.format(KEY_DONATED_WINDOW, agentId);
        Boolean first = redis.opsForValue().setIfAbsent(donatedKey, "1");
        if (!Boolean.TRUE.equals(first)) {
            return;  // already donated this window
        }
        long windowSec = config.getBudget().getResetWindowSeconds();
        redis.expire(donatedKey, windowSec, TimeUnit.SECONDS);

        // Guard 2: skip if this agent borrowed this window (prevents ping-pong)
        String borrowedKey = String.format(KEY_BORROWED_WINDOW, agentId);
        if (Boolean.TRUE.equals(redis.hasKey(borrowedKey))) {
            redis.delete(donatedKey);  // release the lock — no donation this window
            return;
        }

        // Guard 3: only donate when the pool is low (< 50% of initial seed)
        ensurePoolInitialised();
        long poolSize       = parseLong(redis.opsForValue().get(GLOBAL_POOL_KEY));
        long initialTokens  = config.getPool().getInitialTokens();
        if (poolSize >= (long)(initialTokens * POOL_LOW_THRESHOLD_FRACTION)) {
            // Pool is healthy — no donation needed
            redis.delete(donatedKey);
            return;
        }

        double donateThresholdPct = config.getPool().getDonateThresholdPct();
        double donateFraction     = config.getPool().getDonateFraction();
        double usagePct = (tokensUsed * 100.0) / tokenBudget;
        if (usagePct >= donateThresholdPct) {
            redis.delete(donatedKey);
            return;  // agent is not idle
        }

        long remaining = tokenBudget - tokensUsed;
        long donation  = (long)(remaining * donateFraction);
        if (donation < MIN_DONATE_TOKENS) {
            redis.delete(donatedKey);
            return;
        }

        redis.opsForValue().increment(GLOBAL_POOL_KEY, donation);

        // Deduct donated tokens from the donor's budget (genuine transfer, not creation)
        long newBudget = Math.max(tokensUsed, tokenBudget - donation);
        redis.opsForValue().set(
            String.format("agent:%s:tokens:budget", agentId),
            String.valueOf(newBudget));

        log.info("Agent [{}] donated {} tokens to global pool (usage={:.1f}%, new budget={})",
                agentId, donation, usagePct, newBudget);

        Counter.builder("gateway_pool_donated_tokens_total")
                .description("Tokens donated to the global sharing pool")
                .tag("agent", agentId)
                .register(meters)
                .increment(donation);
    }

    // ── Borrowing ──────────────────────────────────────────────────────────────

    /**
     * Attempts to borrow tokens from the global pool when an agent is starving
     * (approaching or over its own budget).  Returns the number of tokens
     * actually borrowed (may be 0 if the pool is empty).
     *
     * @param agentId the borrowing agent
     * @return tokens borrowed; 0 if pool is empty or below minimum threshold
     */
    public long tryBorrow(String agentId) {
        ensurePoolInitialised();
        long maxBorrowTokens = config.getPool().getMaxBorrowTokens();

        String raw = redis.opsForValue().get(GLOBAL_POOL_KEY);
        long available = parseLong(raw);

        if (available <= 0) {
            log.warn("Agent [{}] tried to borrow but global pool is empty", agentId);
            Counter.builder("gateway_pool_borrow_failures_total")
                    .description("Borrow attempts that failed due to empty pool")
                    .tag("agent", agentId)
                    .register(meters)
                    .increment();
            return 0L;
        }

        long toBorrow = Math.min(available, maxBorrowTokens);

        // Atomic decrement — safe under concurrent borrow requests
        Long afterBorrow = redis.opsForValue().increment(GLOBAL_POOL_KEY, -toBorrow);

        // Guard against over-draft: if pool went negative, put the overage back
        if (afterBorrow != null && afterBorrow < 0) {
            redis.opsForValue().increment(GLOBAL_POOL_KEY, -afterBorrow); // add back excess
            toBorrow = toBorrow + afterBorrow; // reduce what we actually took
        }

        if (toBorrow <= 0) {
            log.warn("Agent [{}] borrow race lost — pool exhausted concurrently", agentId);
            Counter.builder("gateway_pool_borrow_failures_total")
                    .description("Borrow attempts that failed due to empty pool")
                    .tag("agent", agentId)
                    .register(meters)
                    .increment();
            return 0L;
        }

        log.info("Agent [{}] borrowed {} tokens from global pool (pool remaining: {})",
                agentId, toBorrow, afterBorrow);

        // Mark this agent as a borrower for this budget window so it cannot
        // also donate in the same window (prevents immediate ping-pong).
        String borrowedKey = String.format(KEY_BORROWED_WINDOW, agentId);
        redis.opsForValue().setIfAbsent(borrowedKey, "1");
        redis.expire(borrowedKey, config.getBudget().getResetWindowSeconds(), TimeUnit.SECONDS);

        Counter.builder("gateway_pool_borrowed_tokens_total")
                .description("Tokens borrowed from the global sharing pool")
                .tag("agent", agentId)
                .register(meters)
                .increment(toBorrow);

        return toBorrow;
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    /**
     * Returns the current global pool size for monitoring / stats endpoints.
     */
    public long getPoolSize() {
        return parseLong(redis.opsForValue().get(GLOBAL_POOL_KEY));
    }

    public void setPoolSize(long size) {
        redis.opsForValue().set(GLOBAL_POOL_KEY, String.valueOf(size));
        log.info("Global pool size set to {} tokens", size);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0L;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
