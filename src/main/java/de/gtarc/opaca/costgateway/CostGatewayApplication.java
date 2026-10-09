package de.gtarc.opaca.costgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OPACA Cost-Aware Gateway Application.
 *
 * A Spring Boot microservice that sits in front of the OPACA Runtime Platform
 * and provides:
 * - Token usage tracking per agent (stored in Redis)
 * - Configurable per-agent token budgets with enforcement
 * - Multi-tier model routing (OPACA → Groq → Gemini) based on budget usage
 * - Prometheus metrics for observability
 */
@SpringBootApplication
public class CostGatewayApplication {

    public static final String VERSION = "0.1-SNAPSHOT";

    public static void main(String[] args) {
        SpringApplication.run(CostGatewayApplication.class, args);
    }
}
