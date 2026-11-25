package community.campus;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
class Infrastructure {
    @Bean
    HealthIndicator campusRedis(RedisConnectionFactory factory) {
        return () -> {
            try (var connection = factory.getConnection()) {
                return "PONG".equals(connection.ping()) ? Health.up().build() : Health.down().build();
            } catch (RuntimeException error) {
                return Health.down(error).build();
            }
        };
    }

    @Bean
    OpenAPI documentation() {
        return new OpenAPI().info(new Info().title("Soleil API").version("v1"))
            .components(new Components().addSecuritySchemes("accessToken", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer authorizeProtectedOperations() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/v1/auth/") || path.equals("/api/v1/auth/password")) {
                item.readOperations().forEach(operation -> operation.addSecurityItem(new SecurityRequirement().addList("accessToken")));
            }
        });
    }
}
