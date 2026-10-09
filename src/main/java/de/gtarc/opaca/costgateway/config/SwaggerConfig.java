package de.gtarc.opaca.costgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI configuration for the Cost-Aware Gateway.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OPACA Cost-Aware Gateway API")
                        .description("Cost-aware API gateway for the OPACA multi-agent platform. "
                                + "Provides token usage tracking, budget enforcement, and multi-tier "
                                + "model routing (OPACA → Groq → Gemini).")
                        .version("0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("Capstone Team")
                                .email("karthik@vit.ac.in"))
                        .license(new License()
                                .name("OPACA Non-Commercial License")));
    }
}
