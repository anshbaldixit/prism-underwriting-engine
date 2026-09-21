package dev.anshdixit.prism.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal per-client token bucket for the sensitive endpoints (login, submit, copilot). In production this
 * sits at the API gateway / WAF; here it demonstrates the control and stops a runaway script from burning
 * LLM budget. 30 requests per minute per client IP.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int CAPACITY = 30;
    private static final long REFILL_MS = 60_000;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        boolean sensitive = p.startsWith("/api/auth/login") || (p.equals("/api/applications") && "POST".equals(request.getMethod())) || p.startsWith("/api/copilot");
        return !sensitive;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Bucket b = buckets.computeIfAbsent(request.getRemoteAddr(), k -> new Bucket());
        if (!b.tryConsume()) {
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Rate limit exceeded; retry in a minute\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Bucket {
        private double tokens = CAPACITY;
        private long last = System.currentTimeMillis();

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(CAPACITY, tokens + (now - last) * (CAPACITY / (double) REFILL_MS));
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
