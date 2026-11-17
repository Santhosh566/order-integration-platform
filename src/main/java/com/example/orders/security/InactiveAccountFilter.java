package com.example.orders.security;

import com.example.orders.service.AccountStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses every API call from a user an admin turned off, after the token has been checked.
 * They may still read /api/me (so the page knows to show "contact the administrator") and the
 * public catalogue. Not a @Component: SecurityConfig puts it in the security chain, and Spring
 * Boot would otherwise also run it on every request outside that chain.
 */
public class InactiveAccountFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Your account is inactive. Please contact the administrator.";

    private final CurrentUser currentUser;
    private final AccountStatusService accounts;

    public InactiveAccountFilter(CurrentUser currentUser, AccountStatusService accounts) {
        this.currentUser = currentUser;
        this.accounts = accounts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/me") || path.startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var viewer = currentUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (viewer.isPresent() && !accounts.isActive(viewer.get().userId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":\"ACCOUNT_INACTIVE\",\"reason\":\"" + MESSAGE + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
