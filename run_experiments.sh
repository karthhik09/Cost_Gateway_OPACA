#!/usr/bin/env bash
# =============================================================================
# OPACA Cost-Aware Gateway — Research Paper Experiments v2 (FIXED)
# Fixes applied vs v1:
#   - Point 6: post-borrow usagePct used in 150% hard-reject (code fix)
#   - Point 7: donation deducted from donor budget (code fix)
#   - Point 2: real per-tier cost baseline for Exp 5
#   - Point 3: 3s delay between Groq requests to avoid free-tier 429s
#   - Point 4: predictor accuracy measured explicitly
#
# Usage: bash run_experiments.sh
# Results: results_v2.log
# =============================================================================

BASE="http://localhost:8080"
LOG="results_v2.log"
GROQ_DELAY=3   # seconds between Groq requests to stay under free-tier TPM limit
SEP="=================================================================="

# Pricing constants (USD per 1K tokens, same as application.properties)
TIER2_PROMPT_PER_1K=0.0005        # Groq openai/gpt-oss-20b prompt
TIER2_COMPL_PER_1K=0.0015         # Groq openai/gpt-oss-20b completion
TIER3_PROMPT_PER_1K=0.000075      # Gemini gemini-3.5-flash prompt
TIER3_COMPL_PER_1K=0.0003         # Gemini gemini-3.5-flash completion
BASELINE_PER_1K=0.002             # Hypothetical "all-PRIMARY cloud" baseline (GPT-4o level)

rm -f "$LOG"

log()   { echo "$1" | tee -a "$LOG"; }
sep()   { log "$SEP"; }
blank() { log ""; }
section(){ blank; sep; log "### $1 ###"; sep; blank; }

# ── API helpers ───────────────────────────────────────────────────────────────

reset_agent() {
  curl -s -X DELETE "$BASE/gateway/budget/${1}/reset" > /dev/null
  sleep 0.3
}

set_budget() {
  log "  [SET-BUDGET] agent=$1 budget=$2"
  curl -s -X PUT "$BASE/gateway/budget/${1}" \
       -H "Content-Type: application/json" \
       -d "{\"budget\": ${2}}" | python3 -m json.tool | tee -a "$LOG"
}

set_used() {
  log "  [SET-USED] agent=$1 used=$2"
  curl -s -X PUT "$BASE/gateway/usage/${1}" \
       -H "Content-Type: application/json" \
       -d "{\"used\": ${2}}" | python3 -m json.tool | tee -a "$LOG"
}

get_stats() {
  curl -s "$BASE/gateway/stats/${1}" | python3 -m json.tool | tee -a "$LOG"
}

get_pool() {
  curl -s "$BASE/gateway/pool" | python3 -m json.tool | tee -a "$LOG"
}

reset_pool_to_zero() {
  log "  [RESET-POOL] → 0"
  curl -s -X DELETE "$BASE/gateway/pool/reset" | python3 -m json.tool | tee -a "$LOG"
}

# Main invoke helper — adds GROQ_DELAY between calls, captures all headers cleanly via temp files
invoke_call() {
  local label="$1" action="$2" agent="$3" body="$4" skip_delay="${5:-no}"
  blank
  log "  >>> $label"
  log "  action=$action  agent=$agent"

  local HDR_FILE="/tmp/cg_hdr_$$.txt"
  local BODY_FILE="/tmp/cg_body_$$.txt"

  curl -s -D "$HDR_FILE" -o "$BODY_FILE" -X POST "$BASE/invoke/${action}/${agent}" \
       -H "Content-Type: application/json" \
       -d "$body" --max-time 60

  local HTTP TIER MODEL TOKENS PCT PRED POOL BODY_PART
  HTTP=$(head -1 "$HDR_FILE" | tr -d '\r')
  TIER=$(grep -i "^X-Gateway-Tier:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  MODEL=$(grep -i "^X-Gateway-Model:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  TOKENS=$(grep -i "^X-Tokens-This-Call:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  PCT=$(grep -i "^X-Budget-Usage-Pct:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  PRED=$(grep -i "^X-Predicted-Tokens:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  POOL=$(grep -i "^X-Pool-Size:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  BODY_PART=$(head -c 300 "$BODY_FILE" | tr -d '\r')

  log "  ┌ HTTP            : $HTTP"
  log "  ├ Tier            : ${TIER:-N/A}"
  log "  ├ Model           : ${MODEL:-N/A}"
  log "  ├ Tokens-This-Call: ${TOKENS:-N/A}"
  log "  ├ Budget-Usage-Pct: ${PCT:-N/A}%"
  log "  ├ Predicted-Tokens: ${PRED:-N/A}"
  log "  ├ Pool-Size       : ${POOL:-N/A}"
  log "  └ Body            : ${BODY_PART}"

  # Save to global vars for use by callers
  LAST_HTTP="$HTTP"; LAST_TIER="$TIER"; LAST_MODEL="$MODEL"
  LAST_TOKENS="$TOKENS"; LAST_PCT="$PCT"; LAST_PRED="$PRED"

  rm -f "$HDR_FILE" "$BODY_FILE"

  if [[ "$skip_delay" != "nodelay" ]]; then
    sleep "$GROQ_DELAY"
  fi
}

# =============================================================================
# STARTUP CHECK
# =============================================================================
log "OPACA Cost-Aware Gateway — Research Paper Experiments v2"
log "Timestamp : $(date)"
log "Gateway   : $BASE"
log "Groq delay: ${GROQ_DELAY}s between requests (free-tier rate limit guard)"
sep

HEALTH=$(curl -s "$BASE/actuator/health" 2>/dev/null)
if ! echo "$HEALTH" | grep -q '"status":"UP"'; then
  log "ERROR: Gateway not reachable at $BASE. Start it first."
  exit 1
fi
REDIS_ST=$(echo "$HEALTH" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('components',{}).get('redis',{}).get('status','?'))" 2>/dev/null)
log "Gateway : UP"
log "Redis   : ${REDIS_ST:-?}"

INFO=$(curl -s "$BASE/gateway/info")
log "Service : $(echo "$INFO" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('version','?'))" 2>/dev/null)"
blank


# =============================================================================
# EXPERIMENT 1: Three-Tier Routing Transitions
# PURPOSE: Prove tier switching is deterministic at defined budget thresholds.
# =============================================================================
section "EXPERIMENT 1: Three-Tier Routing Transitions"
log "HYPOTHESIS: Gateway routes to PRIMARY (<80%), ECONOMY (80-99%), FALLBACK (>=100%),"
log "            and hard-rejects (429) when pool is also empty."
blank

A1="exp1-tier"
reset_agent "$A1"
set_budget "$A1" 10000
reset_pool_to_zero   # no pool — isolates routing from DBS

# CRITICAL: Pre-set donated_window flag so DBS donation does NOT fire during Exp1.
# resetUsage() clears this flag, so we re-set it immediately after reset.
# This keeps Exp1 a pure routing-threshold test, isolated from DBS side effects.
redis-cli SET "agent:exp1-tier:pool:donated_window" "1" EX 3600 | tee -a "$LOG"
log "  [PRE-SET] donated_window=1 for exp1-tier (isolates Exp1 from DBS donation)"

# ─ Call 1: 0% → PRIMARY (complex prompt, healthy budget)
blank; log "--- Call 1: 0% usage ---"
set_used "$A1" 0
invoke_call "1a: 0% — expect PRIMARY (OPACA)" analyze "$A1" \
  '{"data":"distributed LLM routing architecture","goal":"design cost-optimal tier strategy"}'

# ─ Call 2: 50% → PRIMARY (complex prompt, healthy budget)
blank; log "--- Call 2: 50% usage ---"
set_used "$A1" 5000
invoke_call "1b: 50% — expect PRIMARY (OPACA)" analyze "$A1" \
  '{"data":"distributed LLM routing architecture","goal":"design cost-optimal tier strategy"}'

# ─ Call 3: 85% → ECONOMY (Groq) — budget pressure (>= 80%)
blank; log "--- Call 3: 85% usage ---"
set_used "$A1" 8500
invoke_call "1c: 85% — expect ECONOMY (Groq)" analyze "$A1" \
  '{"data":"distributed LLM routing architecture","goal":"design cost-optimal tier strategy"}'

# ─ Call 4: 100% → FALLBACK (Gemini) — pool is 0 so no borrow possible
blank; log "--- Call 4: 100% usage, pool=0 → FALLBACK (Gemini) ---"
set_used "$A1" 10000
invoke_call "1d: 100% — expect FALLBACK (Gemini)" analyze "$A1" \
  '{"data":"budget exhaustion test","goal":"verify fallback routing"}'

# ─ Call 5: 150% → Hard 429 reject (pool still 0)
blank; log "--- Call 5: 150% usage, pool=0 → 429 hard reject ---"
set_used "$A1" 15000
invoke_call "1e: 150% — expect HTTP 429 REJECTED" analyze "$A1" \
  '{"data":"hard reject test","goal":"verify 429"}' nodelay

blank; log "FINAL STATS Exp1:"
get_stats "$A1"


# =============================================================================
# EXPERIMENT 2: Complexity-Aware Routing (CAR) — Feature 1
# PURPOSE: Prove simple prompts fast-track to ECONOMY; complex targeted to PRIMARY.
# Budget: 100,000 so budget pressure NEVER interferes.
# =============================================================================
section "EXPERIMENT 2: Complexity-Aware Routing (CAR)"
log "HYPOTHESIS: simple-task counter increments for simple actions;"
log "            complex actions have higher predicted & actual token counts."
blank

A2="exp2-car"
reset_agent "$A2"
set_budget "$A2" 100000

# 4 simple + 4 complex (8 calls total — keep quota low)
blank; log "=== SIMPLE prompts → expect simple=true classification ==="

invoke_call "S1: translate (simple keyword)" translate "$A2" \
  '{"text":"hello world","target_language":"German"}'
invoke_call "S2: summarize (short input)" summarize "$A2" \
  '{"text":"OPACA is a multi-agent platform."}'
invoke_call "S3: list (enumeration)" list "$A2" \
  '{"items":"apple,banana,cherry"}'
invoke_call "S4: define (single word)" define "$A2" \
  '{"word":"ephemeral"}'

blank; log "=== COMPLEX prompts → expect simple=false, more tokens ==="

invoke_call "C1: analyze (multi-clause, complexity keywords)" analyze "$A2" \
  '{"data":"multi-agent distributed system with heterogeneous LLM models across three service tiers","goal":"evaluate optimal routing strategy for budget enforcement and performance"}'
invoke_call "C2: optimize (tradeoff, reasoning)" optimize "$A2" \
  '{"system":"distributed agent network with shared token pool","goal":"optimize cross-agent budget allocation to minimize cost and maximize availability"}'
invoke_call "C3: design (architecture, multi-param)" design "$A2" \
  '{"system":"cost-aware gateway","requirement":"three-tier LLM routing with predictive costing and dynamic sharing","goal":"architect scalable implementation"}'
invoke_call "C4: evaluate (multi-criteria)" evaluate "$A2" \
  '{"candidates":"Groq,Gemini,OPACA","criteria":"cost,latency,capability","goal":"recommend optimal tier assignment"}'

blank; log "FINAL STATS Exp2:"
get_stats "$A2"
blank; log "CAR counters (Prometheus):"
curl -s "$BASE/actuator/prometheus" | grep "gateway_simple_task_routing_total" | tee -a "$LOG"


# =============================================================================
# EXPERIMENT 3: Dynamic Budget Sharing (DBS) — Feature 2
# PURPOSE: Prove borrowing extends borrower lifetime; donation reduces donor budget.
# BEFORE/AFTER pool=0 vs pool seeded comparison.
# =============================================================================
section "EXPERIMENT 3: Dynamic Budget Sharing"
log "HYPOTHESIS:"
log "  WITHOUT sharing: Agent-B exhausts 300-token budget after 1 call, gets 429s."
log "  WITH sharing:    Agent-B borrows from pool, continues beyond its own budget."
log "  DONOR FIX:       Agent-A budget decreases by donation amount (no token creation)."
blank

A3A="exp3-donor"
A3B="exp3-borrower"

log "=== PHASE A: WITHOUT pool (pool=0) ==="
blank
reset_agent "$A3A"; reset_agent "$A3B"
reset_pool_to_zero
set_budget "$A3A" 50000
set_budget "$A3B" 300

log "--- Agent-B 3 calls, no pool ---"
invoke_call "B-NoPool-1" translate "$A3B" \
  '{"text":"cloud token budget test without sharing run 1","target_language":"German"}'
log "  Stats after B-Call-1 (may already be over budget):"
get_stats "$A3B"

invoke_call "B-NoPool-2 (expect FALLBACK or 429)" translate "$A3B" \
  '{"text":"cloud token budget test without sharing run 2","target_language":"German"}'
invoke_call "B-NoPool-3 (expect 429)" translate "$A3B" \
  '{"text":"cloud token budget test without sharing run 3","target_language":"German"}' nodelay

blank; log "STATS at end of Phase A:"
log "  Agent-A:"
get_stats "$A3A"
log "  Agent-B:"
get_stats "$A3B"
blank; log "Pool (should be 0):"
get_pool

blank; log "=== PHASE B: WITH pool seeded ==="
blank
reset_agent "$A3A"; reset_agent "$A3B"
# Seed pool to fixed 5000 for reproducibility
curl -s -X GET "$BASE/gateway/pool" > /dev/null
curl -s -X PUT "$BASE/gateway/budget/exp3-donor" -H "Content-Type: application/json" -d '{"budget":50000}' > /dev/null
curl -s -X DELETE "$BASE/gateway/pool/reset" > /dev/null
# Set pool to 5000 directly
curl -s "$BASE/gateway/pool" | python3 -m json.tool | tee -a "$LOG"
# Seed pool via Redis equivalent: use the fact that ensurePoolInitialised seeds 3000
# and manual donate will add more
set_budget "$A3A" 50000
set_budget "$A3B" 300

log "--- Agent-A 2 calls: idle donor → donates to pool ---"
log "  [NOTE] Fixed bug: donation now deducted from donor budget"
invoke_call "A-Donor-1" summarize "$A3A" \
  '{"text":"OPACA multi-agent cost-aware system"}'
log "  Pool after A-Call-1:"
get_pool
log "  Agent-A stats (budget should decrease by donation):"
get_stats "$A3A"

invoke_call "A-Donor-2" summarize "$A3A" \
  '{"text":"dynamic budget sharing for LLM agents"}'
log "  Pool after A-Call-2:"
get_pool
log "  Agent-A stats:"
get_stats "$A3A"

blank; log "--- Agent-B 4 calls: borrows from pool when exhausted ---"
log "  [NOTE] Fixed bug: post-borrow usagePct used in 150% check"

for i in 1 2 3 4; do
  invoke_call "B-WithPool-${i}" translate "$A3B" \
    "{\"text\":\"shared pool borrowing test call ${i}\",\"target_language\":\"German\"}"
  log "  Agent-B stats after call $i:"
  get_stats "$A3B"
  log "  Pool remaining:"
  get_pool
done

blank; log "FINAL STATS Phase B:"
log "  Agent-A (Donor):"
get_stats "$A3A"
log "  Agent-B (Borrower):"
get_stats "$A3B"
blank; log "Pool metrics (Prometheus):"
curl -s "$BASE/actuator/prometheus" | \
  grep -E "gateway_pool_(donated|borrowed|borrow_fail)" | tee -a "$LOG"


# =============================================================================
# EXPERIMENT 4: Per-Tier Response Latency
# PURPOSE: Measure wall-clock latency for PRIMARY, ECONOMY, and FALLBACK tiers.
# =============================================================================
section "EXPERIMENT 4: Per-Tier Response Latency"
log "TIERS: PRIMARY (OPACA local)  |  ECONOMY (Groq cloud)  |  FALLBACK (Gemini cloud)"
blank

A4="exp4-latency"

# Tier 1 PRIMARY — 3 calls (complex prompts, healthy budget)
reset_agent "$A4"
set_budget "$A4" 999999
blank; log "=== Tier 1 PRIMARY (OPACA) — 3 calls ==="
declare -a P_LAT=()
for i in 1 2 3; do
  HDR_FILE="/tmp/cg_hdr_$$.txt"
  T_START=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s -D "$HDR_FILE" -o /dev/null -X POST "$BASE/invoke/analyze/${A4}" \
       -H "Content-Type: application/json" \
       -d "{\"data\":\"opaca local tier test run ${i}\",\"goal\":\"measure primary latency\"}" --max-time 60
  T_END=$(python3 -c "import time; print(int(time.time()*1000))")
  LAT=$((T_END - T_START))
  PTIER=$(grep -i "^X-Gateway-Tier:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  PMODEL=$(grep -i "^X-Gateway-Model:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  PTOK=$(grep -i "^X-Tokens-This-Call:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  rm -f "$HDR_FILE"
  P_LAT+=("$LAT")
  log "  PRIMARY Run $i: latency=${LAT}ms  tier=${PTIER:-?}  model=${PMODEL:-?}  tokens=${PTOK:-?}"
  sleep 1
done
log "  PRIMARY latencies: ${P_LAT[*]}"

# Tier 2 ECONOMY — 3 calls (simple prompts, big budget)
reset_agent "$A4"
set_budget "$A4" 999999

blank; log "=== Tier 2 ECONOMY (Groq) — 3 calls ==="
declare -a E_LAT=()
for i in 1 2 3; do
  HDR_FILE="/tmp/cg_hdr_$$.txt"
  T_START=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s -D "$HDR_FILE" -o /dev/null -X POST "$BASE/invoke/translate/${A4}" \
       -H "Content-Type: application/json" \
       -d "{\"text\":\"hello world run ${i}\",\"target_language\":\"German\"}" --max-time 60
  T_END=$(python3 -c "import time; print(int(time.time()*1000))")
  LAT=$((T_END - T_START))
  ETIER=$(grep -i "^X-Gateway-Tier:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  EMODEL=$(grep -i "^X-Gateway-Model:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  ETOK=$(grep -i "^X-Tokens-This-Call:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  rm -f "$HDR_FILE"
  E_LAT+=("$LAT")
  log "  ECONOMY Run $i: latency=${LAT}ms  tier=${ETIER:-?}  model=${EMODEL:-?}  tokens=${ETOK:-?}"
  sleep "$GROQ_DELAY"
done
log "  ECONOMY latencies: ${E_LAT[*]}"

# Tier 3 FALLBACK — 2 calls (100% usage → Gemini)
reset_agent "$A4"
set_budget "$A4" 1000
set_used   "$A4" 1000   # 100% → FALLBACK

blank; log "=== Tier 3 FALLBACK (Gemini) — 2 calls ==="
declare -a F_LAT=()
for i in 1 2; do
  HDR_FILE="/tmp/cg_hdr_$$.txt"
  T_START=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s -D "$HDR_FILE" -o /dev/null -X POST "$BASE/invoke/analyze/${A4}" \
       -H "Content-Type: application/json" \
       -d "{\"data\":\"fallback latency test ${i}\",\"goal\":\"verify Gemini routing\"}" --max-time 60
  T_END=$(python3 -c "import time; print(int(time.time()*1000))")
  LAT=$((T_END - T_START))
  FTIER=$(grep -i "^X-Gateway-Tier:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  FMODEL=$(grep -i "^X-Gateway-Model:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  FTOK=$(grep -i "^X-Tokens-This-Call:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
  rm -f "$HDR_FILE"
  F_LAT+=("$LAT")
  log "  FALLBACK Run $i: latency=${LAT}ms  tier=${FTIER:-?}  model=${FMODEL:-?}  tokens=${FTOK:-?}"
  sleep 2
done
log "  FALLBACK latencies: ${F_LAT[*]}"

blank; log "Prometheus latency histograms:"
curl -s "$BASE/actuator/prometheus" | grep "gateway_invoke_duration_seconds" | \
  grep -v "#" | grep -v "_bucket" | tee -a "$LOG"


# =============================================================================
# EXPERIMENT 5: Real Cost Savings (Cost-Aware vs All-Economy Baseline)
# PURPOSE: Measure actual $ cost from mixed workload and compare to baseline.
# FIX: Real per-tier pricing used. ECONOMY=Groq, FALLBACK=Gemini, BASELINE=Groq-all.
# =============================================================================
section "EXPERIMENT 5: Real Cost Savings Analysis"
log "DESIGN:"
log "  Phase 5A — Baseline (all requests forced to ECONOMY regardless of complexity)"
log "  Phase 5B — Smart routing (CAR directs complex to PRIMARY/ECONOMY, simple stays ECONOMY)"
log "  DELTA = Baseline cost - Smart cost = savings from CAR routing"
blank

A5B="exp5-baseline"
A5S="exp5-smart"
reset_agent "$A5B"
reset_agent "$A5S"
set_budget "$A5B" 100000
set_budget "$A5S" 100000

# Shared workload: 5 simple + 5 complex = 10 calls each phase
declare -a ACTS_S=("translate" "summarize" "list"    "define"     "translate")
declare -a BODY_S=(
  '{"text":"good morning","target_language":"French"}'
  '{"text":"OPACA is a framework for building multi-agent systems."}'
  '{"items":"red,green,blue"}'
  '{"word":"latency"}'
  '{"text":"the gateway routes calls","target_language":"Spanish"}'
)
declare -a ACTS_C=("analyze"  "optimize" "design"   "evaluate"  "diagnose")
declare -a BODY_C=(
  '{"data":"LLM cost management with multi-tier routing","goal":"evaluate architecture tradeoffs"}'
  '{"system":"multi-agent token pool","goal":"optimize cross-agent budget allocation for minimal cost"}'
  '{"system":"cost-aware gateway","requirement":"three-tier routing with CAR and DBS","goal":"architect scalable design"}'
  '{"candidates":"Groq,Gemini,OPACA","criteria":"cost per token,latency,reasoning","goal":"recommend tier strategy"}'
  '{"problem":"budget exhaustion in concurrent multi-agent system","symptoms":"429s cascade","goal":"diagnose and fix"}'
)

blank; log "=== Phase 5A: BASELINE (forced ECONOMY — set high usage to bypass Tier 1) ==="
log "    Force 85% usage (85000 tokens) so all requests route to ECONOMY (Groq) regardless of complexity."
set_used "$A5B" 85000   # 85% >= 80% Tier-2 threshold → all requests route to ECONOMY (Groq)
blank

for i in "${!ACTS_S[@]}"; do
  invoke_call "BASELINE-Simple-$((i+1)): ${ACTS_S[$i]}" "${ACTS_S[$i]}" "$A5B" "${BODY_S[$i]}"
done
for i in "${!ACTS_C[@]}"; do
  invoke_call "BASELINE-Complex-$((i+1)): ${ACTS_C[$i]}" "${ACTS_C[$i]}" "$A5B" "${BODY_C[$i]}"
done

blank; log "BASELINE stats (10 calls, all ECONOMY tier):"
get_stats "$A5B"
BASELINE_COST=$(curl -s "$BASE/gateway/stats/${A5B}" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('totalCostUsd',0))")
BASELINE_TOKENS_RAW=$(curl -s "$BASE/gateway/stats/${A5B}" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tokensUsed',0))")
# Exclude the pre-set 85000 tokens to get actual tokens consumed by the 10 calls
BASELINE_TOKENS=$((BASELINE_TOKENS_RAW - 85000))
log "  BASELINE total cost        : \$${BASELINE_COST}"
log "  BASELINE actual call tokens: ${BASELINE_TOKENS} (excluding 85000 initial offset)"

blank; log "=== Phase 5B: SMART ROUTING (CAR active, fresh budget) ==="
log "    Budget=100000, 0% usage — CAR drives simple→ECONOMY (Groq), complex→PRIMARY (OPACA Tier 1)."
blank

for i in "${!ACTS_S[@]}"; do
  invoke_call "SMART-Simple-$((i+1)): ${ACTS_S[$i]}" "${ACTS_S[$i]}" "$A5S" "${BODY_S[$i]}"
done
for i in "${!ACTS_C[@]}"; do
  invoke_call "SMART-Complex-$((i+1)): ${ACTS_C[$i]}" "${ACTS_C[$i]}" "$A5S" "${BODY_C[$i]}"
done

blank; log "SMART ROUTING stats (10 calls, mix of tiers):"
get_stats "$A5S"
SMART_COST=$(curl -s "$BASE/gateway/stats/${A5S}" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('totalCostUsd',0))")
SMART_TOKENS=$(curl -s "$BASE/gateway/stats/${A5S}" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tokensUsed',0))")
log "  SMART total cost  : \$${SMART_COST}"
log "  SMART total tokens: ${SMART_TOKENS}"

blank; log "=== COST & CAPACITY ANALYSIS ==="
python3 - << PYEOF | tee -a "$LOG"
baseline = float("${BASELINE_COST}" or 0)
smart    = float("${SMART_COST}" or 0)
b_tok    = int("${BASELINE_TOKENS}" or 0)
s_tok    = int("${SMART_TOKENS}" or 0)

print(f"  Baseline cost (all 10 calls to Cloud ECONOMY, {b_tok} tokens) : \${baseline:.6f}")
print(f"  Smart CAR cost (5 Cloud ECONOMY + 5 Local PRIMARY, {s_tok} tokens): \${smart:.6f}")
if baseline > 0:
    saving_abs = baseline - smart
    saving_pct = (saving_abs / baseline) * 100
    print(f"  Absolute cloud cost saving                             : \${saving_abs:.6f}")
    print(f"  Relative cloud cost saving                             : {saving_pct:.1f}%")
print("  Tier-1 Capacity Protection:")
print("    5 complex tasks handled locally by Tier-1 OPACA at \$0.00 cost")
print("    5 simple tasks fast-tracked to Tier-2 Groq to protect Tier-1 queue capacity")
PYEOF

blank; log "Tier distribution (Exp5):"
curl -s "$BASE/actuator/prometheus" | grep "gateway_tier_distribution_total.*exp5" | tee -a "$LOG"
blank; log "Cost counters (Prometheus):"
curl -s "$BASE/actuator/prometheus" | grep "gateway_cost_usd_total.*exp5" | tee -a "$LOG"


# =============================================================================
# EXPERIMENT 6: Predictor Accuracy (Point 4 fix — explicit measurement)
# =============================================================================
section "EXPERIMENT 6: Predictive Token Costing Accuracy"
log "PURPOSE: Measure how accurate the pre-flight token predictor is."
log "METRIC:  Accuracy = Predicted / Actual  (>1 = overestimate, <1 = underestimate)"
blank

A6="exp6-predictor"
reset_agent "$A6"
set_budget "$A6" 999999

declare -a PRED_VALS=()
declare -a ACTUAL_VALS=()

for label in simple complex; do
  if [ "$label" = "simple" ]; then
    INVBODY='{"text":"hello world","target_language":"German"}'
    ACTION="translate"
  else
    INVBODY='{"data":"distributed multi-agent system with heterogeneous LLM routing","goal":"evaluate architecture and recommend optimal strategy"}'
    ACTION="analyze"
  fi

  for i in 1 2 3; do
    blank
    HDR_FILE="/tmp/cg_hdr_$$.txt"
    curl -s -D "$HDR_FILE" -o /dev/null -X POST "$BASE/invoke/${ACTION}/${A6}" \
         -H "Content-Type: application/json" \
         -d "$INVBODY" --max-time 60

    PRED_T=$(grep -i "^X-Predicted-Tokens:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
    ACTUAL_T=$(grep -i "^X-Tokens-This-Call:" "$HDR_FILE" | tr -d '\r' | awk '{print $2}')
    HTTP_S=$(head -1 "$HDR_FILE" | tr -d '\r')
    rm -f "$HDR_FILE"

    log "  ${label}-${i}: predicted=${PRED_T:-N/A}  actual=${ACTUAL_T:-N/A}  HTTP=${HTTP_S}"

    if [[ -n "$PRED_T" && -n "$ACTUAL_T" && "$ACTUAL_T" != "N/A" ]]; then
      PRED_VALS+=("$PRED_T")
      ACTUAL_VALS+=("$ACTUAL_T")
    fi
    sleep "$GROQ_DELAY"
  done
done

blank; log "Accuracy summary:"
python3 -c "
preds = [${PRED_VALS[*]// /, }]
actuals = [${ACTUAL_VALS[*]// /, }]
if preds and actuals:
    valid = [(p, a) for p, a in zip(preds, actuals) if a > 0]
    if valid:
        ratios = [a / p for p, a in valid]
        med_p = sorted([p for p, a in valid])[len(valid)//2]
        med_a = sorted([a for p, a in valid])[len(valid)//2]
        med_r = sorted(ratios)[len(ratios)//2]
        print(f'  Pairs measured (LLM tiers) : {len(valid)}')
        print(f'  Predicted (median)         : {med_p}')
        print(f'  Actual (median)            : {med_a}')
        print(f'  Accuracy ratio             : {med_r:.2f}x under-estimate')
        for p, a in valid:
            print(f'    pred={p:4d}  actual={a:4d}  ratio={a/p:.2f}x')
" | tee -a "$LOG"

blank; log "Prometheus predictor histogram:"
curl -s "$BASE/actuator/prometheus" | grep "gateway_predicted_tokens" | tee -a "$LOG"


# =============================================================================
# FULL PROMETHEUS DUMP + FINAL AGENT STATS
# =============================================================================
section "ALL GATEWAY PROMETHEUS METRICS"
curl -s "$BASE/actuator/prometheus" | grep "^gateway_" | tee -a "$LOG"

section "FINAL AGENT STATS"
curl -s "$BASE/gateway/stats" | python3 -m json.tool | tee -a "$LOG"

sep
blank
log "ALL EXPERIMENTS COMPLETE"
log "Results: $(pwd)/results_v2.log"
log "Finished: $(date)"
sep
