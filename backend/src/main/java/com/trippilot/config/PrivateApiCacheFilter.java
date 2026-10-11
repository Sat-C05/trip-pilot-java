package com.trippilot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies before authentication, including failures and event-stream responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PrivateApiCacheFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("CDN-Cache-Control", "no-store");
        response.setHeader("Vercel-CDN-Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        chain.doFilter(request, response);
    }
}
