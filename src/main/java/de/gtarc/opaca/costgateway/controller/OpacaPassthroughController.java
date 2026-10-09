package de.gtarc.opaca.costgateway.controller;

import de.gtarc.opaca.costgateway.config.GatewayConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Passthrough controller that forwards non-invoke OPACA API calls
 * directly to the OPACA Runtime Platform without interception.
 *
 * This ensures the gateway can serve as a complete drop-in replacement
 * for direct OPACA access. Routes like /agents, /containers, /info
 * are forwarded as-is.
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Tag(name = "OPACA Passthrough", description = "Direct passthrough to OPACA platform (no interception)")
public class OpacaPassthroughController {

    private final WebClient opacaWebClient;

    public OpacaPassthroughController(@Qualifier("opacaWebClient") WebClient opacaWebClient) {
        this.opacaWebClient = opacaWebClient;
    }

    @GetMapping("/agents")
    @Operation(summary = "Get agents (passthrough to OPACA)")
    public ResponseEntity<String> getAgents(
            @RequestParam(required = false, defaultValue = "false") boolean includeConnected
    ) {
        log.info("Passthrough: GET /agents");
        String response = opacaWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agents")
                        .queryParam("includeConnected", includeConnected)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/agents/{agentId}")
    @Operation(summary = "Get agent details (passthrough to OPACA)")
    public ResponseEntity<String> getAgent(@PathVariable String agentId) {
        log.info("Passthrough: GET /agents/{}", agentId);
        String response = opacaWebClient.get()
                .uri("/agents/" + agentId)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/containers")
    @Operation(summary = "Get containers (passthrough to OPACA)")
    public ResponseEntity<String> getContainers(
            @RequestParam(required = false, defaultValue = "false") boolean includeConnected
    ) {
        log.info("Passthrough: GET /containers");
        String response = opacaWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/containers")
                        .queryParam("includeConnected", includeConnected)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/opaca/info")
    @Operation(summary = "Get OPACA platform info (passthrough)")
    public ResponseEntity<String> getPlatformInfo() {
        log.info("Passthrough: GET /opaca/info");
        String response = opacaWebClient.get()
                .uri("/info")
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/opaca/history")
    @Operation(summary = "Get OPACA event history (passthrough)")
    public ResponseEntity<String> getHistory() {
        log.info("Passthrough: GET /opaca/history");
        String response = opacaWebClient.get()
                .uri("/history")
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ResponseEntity.ok(response);
    }
}

