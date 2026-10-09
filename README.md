# OPACA Cost-Aware Gateway

A **production-grade cost management layer** for the [OPACA](https://github.com/GT-ARC/opaca-core) multi-agent framework. The gateway sits transparently between clients and the OPACA platform, enforcing per-agent token budgets and automatically routing to cheaper LLM providers when budget thresholds are exceeded.

---

## Architecture

```
Client
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│              Cost-Aware Gateway  :8080                  │
│                                                         │
│  POST /invoke/{action}/{agentId}                        │
│        │                                                │
│        ▼                                                │
│  ┌───────────────┐    ┌──────────────────────────────┐  │
│  │ Redis Budget  │    │     Three-Tier Router         │  │
│  │ Check (INCRBY)│───▶│                              │  │
│  └───────────────┘    │  usage < 80%  → Tier 1       │  │
│                       │  80–100%      → Tier 2       │  │
│                       │  > 100%       → Tier 3       │  │
│                       │  ≥ 150%       → HTTP 429     │  │
│                       └──────────────────────────────┘  │
│        │                                                │
│        ▼                                                │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Prometheus Metrics  /actuator/prometheus        │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
        │              │                │
        ▼              ▼                ▼
   OPACA :8000     Groq API       Gemini API
   (Tier 1)      (Tier 2)        (Tier 3)
   PRIMARY       ECONOMY         FALLBACK
```

### Three-Tier Routing Logic

| Tier | Name | Trigger | Upstream | Model |
|------|------|---------|---------|-------|
| 1 | **PRIMARY** | Usage < 80% of budget | OPACA Platform | Full agent capability |
| 2 | **ECONOMY** | 80% ≤ Usage < 100% | Groq API | `openai/gpt-oss-20b` |
| 3 | **FALLBACK** | Usage ≥ 100% | Gemini API | `gemini-3.8-flash` |
| – | **REJECTED** | Usage ≥ 150% | — | HTTP 429 + `Retry-After` |

---

## Quick Start (Local)

### Prerequisites
- Java 21+ (Java 25 works)
- Maven
- Docker Desktop (for Redis, Prometheus, Grafana)

### Step 1 — Configure API keys
```bash
cp .env.example .env
# Edit .env and fill in your keys (already filled if you received this from setup)
```

### Step 2 — Start infrastructure
```bash
# Make sure Docker Desktop is running first!
docker-compose up -d
docker-compose ps   # all 3 should show "running"
```

### Step 3 — Start OPACA platform
```bash
java -jar /path/to/opaca-platform-*.jar \
  --opaca.security.requireAuth=false \
  --opaca.platform_environment=native
# Runs on http://localhost:8000
```

### Step 4 — Start the gateway
```bash
set -a && source .env && set +a
java -jar target/cost-gateway-0.1-SNAPSHOT.jar
# Runs on http://localhost:8080
```

### Step 5 — Verify everything is up
```bash
# Gateway health (should show redis.status: UP)
curl http://localhost:8080/actuator/health | python3 -m json.tool

# Open Grafana  (admin / admin)
open http://localhost:3000

# Open Swagger UI
open http://localhost:8080/swagger-ui.html
```

---

## Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Cost-Aware Gateway | http://localhost:8080 | — |
| Gateway Swagger UI | http://localhost:8080/swagger-ui.html | — |
| Gateway Stats API | http://localhost:8080/gateway/stats | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin |
| OPACA Platform | http://localhost:8000 | — |
| Redis CLI | `docker exec -it opaca-redis redis-cli` | — |

---

## API Reference

### Invoke (core endpoint)
```
POST /invoke/{action}
POST /invoke/{action}/{agentId}
Content-Type: application/json

Body: { "param1": "value1", "param2": "value2" }
```

**Response headers on success:**
```
X-Gateway-Tier:        PRIMARY | ECONOMY | FALLBACK
X-Gateway-Model:       openai/gpt-oss-20b | gemini-3.8-flash | opaca-platform
X-Tokens-This-Call:    <count>
X-Tokens-Window:       <cumulative count this window>
X-Budget-Usage-Pct:    <e.g. 82.3>
```

**Response on budget exhaustion (HTTP 429):**
```json
{
  "errorCode": "TOKEN_BUDGET_EXCEEDED",
  "error": "Token budget exhausted for agent 'agent-1' (used 1500 / 1000 tokens). Resets in 3240s.",
  "tier": "REJECTED"
}
```
Headers: `Retry-After: 3240`

### Budget Management
```
PUT    /gateway/budget/{agentId}         Set token budget
DELETE /gateway/budget/{agentId}/reset   Reset counters
GET    /gateway/stats                    All agents
GET    /gateway/stats/{agentId}          Single agent
GET    /gateway/info                     Gateway metadata
```

---

## Testing with Postman

Import `OPACA-Gateway.postman_collection.json` into Postman.

**Recommended demo sequence:**

1. **Health & Info** — show gateway is up with Redis connected
2. **Set Budget (10000)** — establish a budget for your agent
3. **Invoke (PRIMARY)** — first calls go to OPACA, tier = PRIMARY
4. **Set Budget TIGHT (50)** — force tier switching fast
5. **Invoke (ECONOMY)** — watch it route to Groq, tier = ECONOMY
6. **Set Budget = 1** — force FALLBACK
7. **Invoke (FALLBACK)** — Gemini responds, tier = FALLBACK
8. **Invoke repeatedly → 429** — trigger hard rejection, check Retry-After header
9. **Stats** — show throttledCount, usagePercent, currentTier

---

## Redis Schema

All keys are plain strings (human-readable in `redis-cli`):

```
agent:{agentId}:tokens:used       ← Long: rolling token counter (expires after window)
agent:{agentId}:tokens:budget     ← Long: per-agent budget limit
agent:{agentId}:cost:usd          ← String: accumulated USD cost
agent:{agentId}:requests:total    ← Long: successful invocations
agent:{agentId}:requests:throttled← Long: 429 rejections
```

Inspect live:
```bash
docker exec -it opaca-redis redis-cli
> KEYS agent:*
> GET agent:research-agent-1:tokens:used
> GET agent:research-agent-1:tokens:budget
> TTL agent:research-agent-1:tokens:used
```

---

## Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `gateway_tokens_used_total` | Counter | Tokens consumed, labeled by agent + tier |
| `gateway_requests_total` | Counter | Successful invocations, labeled by agent + tier + model |
| `gateway_throttle_events_total` | Counter | 429 rejections, labeled by agent |
| `gateway_cost_usd_total` | Counter | Estimated USD cost, labeled by agent + tier |
| `gateway_tier_distribution_total` | Counter | Calls per tier, labeled by agent + tier |
| `gateway_invoke_duration_seconds` | Histogram | Invoke latency with p50/p95/p99, labeled by tier + agent |
| `gateway_budget_usage_percent` | Gauge | Current usage % per agent (real-time) |

---

## Grafana Dashboard Guide

The dashboard auto-loads at http://localhost:3000. It has 5 sections:

### Row 1 — Overview (6 stat panels)
- **Total Requests** — all invocations this hour
- **429 Throttle Events** — red when agents hit budget limits (key metric!)
- **Total Tokens Used** — raw token count
- **Estimated Cost (USD)** — actual dollar amount
- **P95 Latency** — 95th percentile response time
- **Active Agents** — distinct agents tracked

### Row 2 — Token Usage & Budget
- **Tokens Used Per Agent** (timeseries) — who's consuming tokens and when
- **Budget Usage % Per Agent** (bargauge) — green < 80%, yellow 80-100%, orange 100-150%, red > 150%

### Row 3 — Tier Routing & 429 Events
- **Tier Distribution Over Time** — rising ECONOMY/FALLBACK = agents hitting budget limits
- **HTTP 429 Throttle Events** — bar chart, spikes directly from the `gateway_throttle_events_total` counter
- **Tier Share (Total)** — donut chart showing PRIMARY/ECONOMY/FALLBACK split
- **Per-Agent Summary Table** — tokens, cost, throttle count per agent

### Row 4 — Latency
- **Invoke Latency Percentiles** — p50/p95/p99 broken down by tier
- **Latency Distribution** — histogram of all call durations

### Row 5 — Cost Analysis
- **Cost Over Time** — USD/minute per agent
- **Total Cost Per Agent** — bargauge

---

## Kubernetes Deployment (Minikube)

```bash
# Install minikube
brew install minikube
minikube start --cpus=4 --memory=8192

# Enable metrics server (for HPA)
minikube addons enable metrics-server

# Build and load image
mvn package -DskipTests
docker build -t opaca-cost-gateway:1.0.0 .
minikube image load opaca-cost-gateway:1.0.0

# Deploy all resources
kubectl apply -f k8s/

# Wait for pods
kubectl get pods -n opaca-gateway -w

# Get access URLs
minikube service cost-gateway -n opaca-gateway --url   # Gateway
minikube service grafana     -n opaca-gateway --url   # Grafana
minikube service prometheus  -n opaca-gateway --url   # Prometheus

# Watch HPA scale
kubectl get hpa -n opaca-gateway -w
```

---

## Conference Paper — Key Technical Contributions

1. **Cost-Proportional Routing** — Unlike rate limiting (which counts requests), the gateway counts tokens — the actual unit of LLM cost — enabling cost-proportional routing decisions.

2. **Zero-Invasive Architecture** — The gateway mirrors OPACA's REST API exactly. Existing clients switch from OPACA port 8000 to gateway port 8080 with no other changes.

3. **Atomic Budget Enforcement** — Redis `INCRBY` provides atomic counter updates safe under concurrent agent load (no locks, no race conditions).

4. **Observable Cost Telemetry** — Seven Prometheus metrics expose full cost observability: tokens, cost USD, latency percentiles, tier distribution, and 429 events — all in real time.

5. **Kubernetes-Native Scaling** — HPA scales the gateway 2–5 replicas based on CPU. Redis is a single source of truth shared across replicas.
