package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Exact-route guard, registered after Spring Security/CORS and before JSON allocation/validation. */
@RequiredArgsConstructor
public class VenueSuggestionSubmissionFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 4096;
    private final VenueSuggestionRateGuard guard;
    private final SecurityErrorResponseWriter errors;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/api/v1/venue-suggestions".equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { guard.check(request); }
        catch (RateLimitedException limited) {
            response.setHeader("Retry-After", Long.toString(limited.getRetryAfterSeconds()));
            errors.write(request, response, limited.getErrorType()); return;
        } catch (ServiceUnavailableRetryException unavailable) {
            response.setHeader("Retry-After", Long.toString(unavailable.getRetryAfterSeconds()));
            errors.write(request, response, unavailable.getErrorType()); return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            errors.write(request, response, ErrorType.VENUE_SUGGESTION_TOO_LARGE); return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous JSON endpoint"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
}
