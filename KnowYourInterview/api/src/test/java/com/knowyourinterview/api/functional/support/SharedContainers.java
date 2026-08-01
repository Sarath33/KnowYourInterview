package com.knowyourinterview.api.functional.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres and one Redis container for the whole functional suite, started once per JVM.
 *
 * <p>This is the "singleton container" pattern, and it's a deliberate difference from
 * {@link com.knowyourinterview.api.support.ContainerConfig}, which declares its containers as
 * Spring beans. Bean-scoped containers are tied to the Spring context that owns them, so every
 * {@code @SpringBootTest} class with a distinct context gets its own pair — fine for the two
 * pre-existing {@code *FlowIT} classes, prohibitively slow for a suite this size (a dozen-odd
 * classes × ~10s of container startup each, on top of the context startup itself).
 *
 * <p>Static containers are started in a static initializer and never stopped explicitly:
 * Testcontainers' Ryuk sidecar reaps them when the JVM exits. Calling {@code stop()} in a
 * shutdown hook would only race Ryuk to do the same job.
 *
 * <p>The trade-off of sharing containers is shared state — which is why
 * {@link FunctionalTestBase} truncates every table and flushes Redis before each test rather
 * than relying on a fresh database per class. See {@code docs/09-test-plan.md} §5.
 */
public final class SharedContainers {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private SharedContainers() {
    }

    /**
     * Points the application at these containers. Registered from {@link FunctionalTestBase}'s
     * {@code @DynamicPropertySource}; the property names match what {@code application.yml}
     * reads, including the single-URL Redis form ({@code spring.data.redis.url}) the app uses
     * rather than separate host/port properties.
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }
}
