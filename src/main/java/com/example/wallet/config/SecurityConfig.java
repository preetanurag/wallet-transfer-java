package com.example.wallet.config;



import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    // Authentication uses configured bearer tokens exclusively. Disable Boot's
    // default generated username/password account and its startup password log.
    @Bean
    org.springframework.security.core.userdetails.UserDetailsService noPasswordUsers() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Bearer token required");
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper,
            @Value("${wallet.auth-tokens}") String json) throws Exception {
        Map<String, String> users = new HashMap<>();
        var root = mapper.readTree(json);
        if (!root.isObject() || root.isEmpty()) throw new IllegalArgumentException("AUTH_TOKENS must map users to tokens");
        for (var entry : root.properties()) {
            String token = entry.getValue().isString() ? entry.getValue().stringValue() : "";
            if (entry.getKey().isBlank() || token.length() < 16 || token.chars().anyMatch(Character::isWhitespace) ||
                    users.putIfAbsent(digest(token), entry.getKey()) != null) {
                throw new IllegalArgumentException("AUTH_TOKENS needs unique tokens of at least 16 characters without whitespace");
            }
        }
        var bearerFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    String user = users.get(digest(header.substring(7)));
                    if (user != null) SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(user, null, List.of()));
                }
                chain.doFilter(request, response);
            }
        };
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/healthz", "/metrics").permitAll().anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, 401, "unauthorized"))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, res, 403, "forbidden")))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class).build();
    }
    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void writeError(ObjectMapper mapper, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", code, "correlation_id", MDC.get("correlation_id"))));
    }
}
