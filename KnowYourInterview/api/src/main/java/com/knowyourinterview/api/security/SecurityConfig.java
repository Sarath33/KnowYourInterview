package com.knowyourinterview.api.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;

    public SecurityConfig(JwtService jwtService, StringRedisTemplate redisTemplate) {
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Single source of truth for CORS. This has to live here (not a plain
     * WebMvcConfigurer#addCorsMappings, which is what this replaced) because Spring
     * Security's filter chain sits in front of the MVC dispatcher and intercepts
     * preflight OPTIONS requests before MVC-level CORS config ever runs. Wiring it
     * through .cors(...) below tells Security's own CorsFilter to handle preflight
     * (and add the response headers) using this configuration, before authorization
     * rules are evaluated — otherwise preflight requests for any endpoint that isn't
     * permitAll get rejected, which is exactly what broke the protected /experiences
     * endpoints (any request carrying an Authorization header triggers a preflight;
     * /health and /auth/** never did because those calls don't send extra headers).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Stateless JWT API: no cookies/sessions, so CSRF protection (which defends
                // cookie-based auth) doesn't apply here.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No formLogin/httpBasic is configured (stateless JWT API), so Spring
                // Security has no default AuthenticationEntryPoint and falls back to
                // Http403ForbiddenEntryPoint — every unauthenticated request would come
                // back 403 instead of 401. This restores the expected 401.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Actuator only ever exposes health+info over HTTP (see
                        // application.yml's management.endpoints.web.exposure.include) —
                        // nothing more sensitive is reachable under /actuator/**, so a
                        // blanket permitAll here is safe. Health's own show-details:
                        // when-authorized setting (not this rule) is what hides
                        // DB/Redis component detail from non-admins.
                        .requestMatchers("/actuator/**").permitAll()
                        // Razorpay calls this server-to-server with no JWT — the
                        // X-Razorpay-Signature check inside WebhookController is the real
                        // authentication here, not Spring Security.
                        .requestMatchers("/api/v1/payments/webhook").permitAll()
                        // Order matters: rules are evaluated top-to-bottom, first match wins.
                        // "/mine" would otherwise also match the "/api/v1/experiences/*" pattern
                        // below (Ant "*" matches exactly one path segment, and "mine" is one),
                        // so it has to be listed — and require auth — before that broader rule.
                        .requestMatchers(HttpMethod.GET, "/api/v1/experiences/mine").authenticated()
                        // Browse is public (teaser-only unless the JWT filter identifies the
                        // caller as the owner/an admin — see ExperienceService.getPublicView).
                        .requestMatchers(HttpMethod.GET, "/api/v1/experiences", "/api/v1/experiences/*").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                // Anchored to JwtAuthenticationFilter specifically (not the
                // UsernamePasswordAuthenticationFilter anchor above) so it's guaranteed to
                // run strictly before it — rejecting a rate-limited request before bothering
                // to parse its JWT.
                .addFilterBefore(new RateLimitingFilter(redisTemplate), JwtAuthenticationFilter.class);

        return http.build();
    }
}
