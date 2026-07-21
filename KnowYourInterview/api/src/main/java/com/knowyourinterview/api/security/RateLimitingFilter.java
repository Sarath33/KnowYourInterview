package com.knowyourinterview.api.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Blunt, IP-based rate limiting on the auth endpoints most worth throttling
 * (credential stuffing on login, mass-registration abuse, forgot-password email
 * flooding). Redis-backed fixed-window counter — reuses the same Redis instance
 * already required for refresh-token tracking, no new infra.
 *
 * Deliberately IP-only, not per-account (no request-body parsing to pull out the
 * email — that would need a ContentCachingRequestWrapper for not much extra value at
 * this scale). Also doesn't attempt to trust X-Forwarded-For, since there's no
 * configured trusted-proxy list yet — behind a real load balancer/CDN this should be
 * revisited (Spring's ForwardedHeaderFilter + a trusted-proxy allowlist), otherwise
 * X-Forwarded-For is trivially spoofable and this would just rate-limit the proxy.
 *
 * Not a @Component: registered explicitly in SecurityConfig via addFilterBefore, same
 * as JwtAuthenticationFilter — a @Component Filter would additionally get
 * auto-registered as a blanket servlet filter by Spring Boot, running twice.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private record Limit(int maxRequests, Duration window) {}

    private static final Map<String, Limit> LIMITS_BY_PATH = Map.of(
            "/api/v1/auth/login", new Limit(10, Duration.ofMinutes(1)),
            "/api/v1/auth/register", new Limit(5, Duration.ofMinutes(1)),
            "/api/v1/auth/forgot-password", new Limit(5, Duration.ofMinutes(1)),
            "/api/v1/auth/reset-password", new Limit(10, Duration.ofMinutes(1)));

    private final StringRedisTemplate redisTemplate;

    public RateLimitingFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        Limit limit = LIMITS_BY_PATH.get(request.getRequestURI());
        if (limit == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "ratelimit:" + request.getRequestURI() + ":" + request.getRemoteAddr();
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, limit.window());
        }

        if (count != null && count > limit.maxRequests()) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":429,"error":"Too Many Requests",\
                    "message":"Too many attempts from this address — try again in a minute."}""");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
