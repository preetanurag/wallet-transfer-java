package com.example.wallet.observability;



import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-ID");
        String id = supplied != null && supplied.matches("[a-zA-Z0-9_-]{1,64}") ? supplied : UUID.randomUUID().toString();
        MDC.put("correlation_id", id);
        response.setHeader("X-Correlation-ID", id);
        response.setHeader("Cache-Control", "no-store");
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            LOG.atInfo().addKeyValue("event", "request_completed")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000).log("Request completed");
            MDC.remove("correlation_id");
        }
    }
}
