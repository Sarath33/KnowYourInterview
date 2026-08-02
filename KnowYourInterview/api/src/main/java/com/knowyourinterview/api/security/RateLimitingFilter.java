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
 * this scale).
 *
 * <h2>Which IP counts</h2>
 * Whether X-Forwarded-For is trusted is a deployment fact, not a code one, so it's a
 * config flag ({@code app.rate-limit.trust-forwarded-for}, env var
 * {@code TRUST_FORWARDED_FOR}) that defaults to <b>false</b>:
 * <ul>
 *   <li><b>false</b> (local dev, or anything directly internet-facing) — key on
 *       {@code getRemoteAddr()}. Trusting the header here would make the limiter
 *       useless, since any client can send whatever X-Forwarded-For it likes.</li>
 *   <li><b>true</b> (behind a proxy that always overwrites the header — Railway's edge,
 *       an ALB, Cloudflare) — key on the left-most X-Forwarded-For hop, which is the
 *       original client. Without this, every request appears to come from the proxy's
 *       single IP and the limits become global: 10 logins per minute for the entire
 *       user base, not per user. That's the failure mode this flag exists to fix, and
 *       it's the one that was live in production before it existed.</li>
 * </ul>
 * Only set it to true when a trusted proxy really is in front — the flag is the
 * "trusted-proxy allowlist" in its simplest possible form (one bit: is there a proxy or
 * not), which is sufficient because the app is never reachable both ways at once.
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
            "/api/v1/auth/reset-password", new Limit(10, Duration.ofMinutes(1)),
            // Same bucket size as login — verifying a Google ID token is cheap (no network
            // round-trip once Google's JWKS is cached) but this endpoint can still create a
            // new account per call, same abuse shape as /register.
            "/api/v1/auth/google", new Limit(10, Duration.ofMinutes(1)),
            // Tight — this is a brute-forceable secret that grants admin. The endpoint
            // itself is a constant-time comparison (see AuthService#bootstrapAdmin), but a
            // rate limit is still the first line of defense against guessing it at all.
            "/api/v1/auth/bootstrap-admin", new Limit(5, Duration.ofMinutes(1)),
            // Tighter than anything else here, because the abuse doesn't land on us — it
            // lands in a third party's inbox. Someone hammering this with a stranger's
            // address is using the app to send them mail, and a legitimate user needs it
            // roughly once.
            "/api/v1/auth/resend-verification", new Limit(3, Duration.ofMinutes(1)),
            // The third of the three controls that make a 6-digit code safe (the others being
            // its 10-minute life and the 5-guess cap on each code — see
            // EmailVerificationService). The per-code cap is the real defence; this bounds how
            // fast someone can cycle through fresh codes to get fresh budgets, and it's the
            // only one of the three that a distributed attacker can't sidestep by resending.
            "/api/v1/auth/verify-email", new Limit(10, Duration.ofMinutes(1)));

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final StringRedisTemplate redisTemplate;
    private final boolean trustForwardedFor;

    public RateLimitingFilter(StringRedisTemplate redisTemplate, boolean trustForwardedFor) {
        this.redisTemplate = redisTemplate;
        this.trustForwardedFor = trustForwardedFor;
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

        String key = "ratelimit:" + request.getRequestURI() + ":" + clientIp(request);
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

    /**
     * The address to bucket this request under. See the class Javadoc for why this is
     * configurable rather than always one or the other.
     * <p>
     * X-Forwarded-For is a comma-separated chain (client, proxy1, proxy2…) — the left-most
     * entry is the original client. A trusted proxy overwrites rather than appends to any
     * client-supplied header, so that entry is only as trustworthy as the flag says it is.
     * Falls back to getRemoteAddr() when the header is absent or empty, so a request that
     * somehow reaches the app without going through the proxy still gets limited rather
     * than sailing past on a null key.
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String first = forwarded.split(",", 2)[0].trim();
        return first.isEmpty() ? request.getRemoteAddr() : first;
    }
}
