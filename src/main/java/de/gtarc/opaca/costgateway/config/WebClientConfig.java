package de.gtarc.opaca.costgateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Creates named WebClient beans for each upstream (OPACA, Groq, Gemini).
 * Buffer size is set to 16 MB to handle large OPACA agent responses.
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final GatewayConfig config;

    private static final int MAX_BUFFER_MB = 16;

    @Bean("opacaClient")
    public WebClient opacaClient() {
        return WebClient.builder()
                .baseUrl(config.getOpaca().getBaseUrl())
                .exchangeStrategies(largeBuffer())
                .build();
    }

    @Bean("groqClient")
    public WebClient groqClient() {
        return WebClient.builder()
                .baseUrl(config.getGroq().getBaseUrl())
                .exchangeStrategies(largeBuffer())
                .build();
    }

    @Bean("geminiClient")
    public WebClient geminiClient() {
        return WebClient.builder()
                .baseUrl(config.getGemini().getBaseUrl())
                .exchangeStrategies(largeBuffer())
                .build();
    }

    private ExchangeStrategies largeBuffer() {
        return ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BUFFER_MB * 1024 * 1024))
                .build();
    }
}
