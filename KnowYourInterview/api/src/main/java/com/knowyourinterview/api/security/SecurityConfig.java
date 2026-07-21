package com.knowyourinterview.api.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
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
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
