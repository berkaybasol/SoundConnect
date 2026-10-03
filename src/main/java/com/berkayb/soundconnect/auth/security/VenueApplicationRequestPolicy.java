package com.berkayb.soundconnect.auth.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;
import java.util.regex.Pattern;

/** Exact routes only; no prefix, encoded-path, extra-query or method widening. */
public final class VenueApplicationRequestPolicy {
    public static final String BASE = "/api/v1/venue-application-session/applications/";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern NOTIFICATION = Pattern.compile("/notifications/" + UUID_PATTERN);
    private static final Pattern READ = Pattern.compile("/notifications/" + UUID_PATTERN + "/read");
    private static final Pattern DEVICE = Pattern.compile("/push/devices/" + UUID_PATTERN);
    private static final Pattern REVISION = Pattern.compile("clientRevision=[1-9][0-9]{0,18}");

    private VenueApplicationRequestPolicy() {}

    public static boolean allows(HttpServletRequest request, UUID applicationId) {
        if (applicationId == null || request.getRequestURI() == null) return false;
        String base = BASE + applicationId;
        String path = request.getRequestURI();
        if (!path.startsWith(base)) return false;
        String suffix = path.substring(base.length());
        String method = request.getMethod();
        String query = request.getQueryString();
        if ("DELETE".equals(method) && DEVICE.matcher(suffix).matches()) {
            if (query == null || !REVISION.matcher(query).matches()) return false;
            try { return Long.parseLong(query.substring("clientRevision=".length())) > 0; }
            catch (NumberFormatException exception) { return false; }
        }
        if (query != null) return false;
        return ("GET".equals(method) && (suffix.isEmpty() || NOTIFICATION.matcher(suffix).matches()))
                || ("POST".equals(method) && ("/promote".equals(suffix)
                    || "/notifications/delivery-state".equals(suffix) || READ.matcher(suffix).matches()))
                || ("PUT".equals(method) && DEVICE.matcher(suffix).matches());
    }
}
