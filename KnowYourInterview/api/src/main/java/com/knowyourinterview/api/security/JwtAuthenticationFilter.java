package com.knowyourinterview.api.security;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads "Authorization: Bearer <access-token>", validates it, and — if valid —
 * populates the SecurityContext with an AuthenticatedUser principal and
 * ROLE_USER (+ ROLE_ADMIN if applicable) authorities. No DB/Redis lookup here;
 * access tokens are trusted as-is until they expire (they're short-lived by design).
 * Invalid/missing tokens simply leave the context unauthenticated — SecurityConfig
 * decides which endpoints require authentication.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
                AuthenticatedUser principal =
                        new AuthenticatedUser(claims.userId(), claims.email(), claims.admin());

                List<GrantedAuthority> authorities =
                        claims.admin()
                                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                                : List.of(new SimpleGrantedAuthority("ROLE_USER"));

                var authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid/expired token: leave unauthenticated, let SecurityConfig's
                // access rules reject with 401/403 as appropriate.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
