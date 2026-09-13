package com.dinesh.wallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests. Spins up a real Postgres via Testcontainers
 * (requires a running Docker) so the concurrency behaviour is exercised against
 * the actual database engine, not an in-memory stand-in.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void flyway(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
