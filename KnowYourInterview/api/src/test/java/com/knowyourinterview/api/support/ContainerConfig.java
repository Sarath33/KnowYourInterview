package com.knowyourinterview.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres + Redis for *IT.java tests, via Testcontainers. Import this into a
 * @SpringBootTest and Spring Boot auto-wires spring.datasource.* / spring.data.redis.*
 * to point at these containers — no manual @DynamicPropertySource needed. Flyway runs
 * against the container on context startup exactly like it does against the real
 * docker-compose Postgres in local dev.
 *
 * Bean-scoped (not @Testcontainers + static @Container fields) so Spring manages the
 * container lifecycle alongside the rest of the context — every *IT.java class that
 * imports this gets its own fresh pair of containers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
