package com.berkayb.soundconnect.shared.recovery;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Before security/rate limiting: a held request must not touch Redis or OTP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RecoveryHttpFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (RecoveryRuntime.isHeld()) {
            response.setStatus(503);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"RECOVERY_HOLD_AUTHORITY_UNPROVEN\",\"canApply\":false}");
            return;
        }
        chain.doFilter(request, response);
    }
}
