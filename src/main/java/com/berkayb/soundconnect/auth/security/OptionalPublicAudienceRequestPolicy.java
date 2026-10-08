package com.berkayb.soundconnect.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;

/** Guest reads remain public; an explicitly supplied session must not become a guest projection. */
public final class OptionalPublicAudienceRequestPolicy {
    private static final Pattern PROFILE_MEDIA = Pattern.compile("^/api/v1/profiles/[^/]+/[^/]+/media$");
    private static final List<String> BASES = List.of(
            "/api/v1/public/media", "/api/v1/public/musician-profiles", "/api/v1/public/bands",
            "/api/v1/public/venue-profiles", "/api/v1/public/studio-profiles",
            "/api/v1/promotions/displayable");

    private OptionalPublicAudienceRequestPolicy() { }

    public static boolean requiresAuthenticatedBearer(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        if (path == null) return false;
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        final String applicationPath = path;
        boolean audienceAware = "/api/v1/public/search/profiles".equals(path) || PROFILE_MEDIA.matcher(path).matches()
                || BASES.stream().anyMatch(base -> applicationPath.equals(base) || applicationPath.startsWith(base + "/"));
        if (!audienceAware) return false;
        String authorization = request.getHeader("Authorization");
        if (authorization == null) return false;
        String value = authorization.strip();
        return value.equalsIgnoreCase("Bearer") || value.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
