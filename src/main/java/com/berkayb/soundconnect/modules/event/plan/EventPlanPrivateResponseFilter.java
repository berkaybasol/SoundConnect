package com.berkayb.soundconnect.modules.event.plan;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Includes malformed requests and authorization errors, which never enter a controller. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class EventPlanPrivateResponseFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        return !path.startsWith("/api/v1/venue-owner/event-plans")&&!path.startsWith("/api/v1/user/event-plans");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store, private");chain.doFilter(request,response);
    }
}
