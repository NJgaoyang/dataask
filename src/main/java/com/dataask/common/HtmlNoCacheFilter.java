package com.dataask.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Vite asset filenames are content hashed, but HTML must not be cached or
 * browsers can keep loading an older bundle after a JAR replacement.
 */
@Component
public class HtmlNoCacheFilter extends OncePerRequestFilter {
    private static final Set<String> NO_CACHE = Set.of("/", "/index.html", "/build-info.json");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (NO_CACHE.contains(request.getServletPath())) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        chain.doFilter(request,response);
    }
}
