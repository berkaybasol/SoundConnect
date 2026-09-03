package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Server-authoritative policy for listener visibility onboarding.
 *
 * <p>The client-side chooser is navigation, not an authorization boundary. A
 * valid JWT is intentionally issued before the listener chooses a visibility
 * mode, so every other product endpoint must remain inaccessible until that
 * explicit choice has been persisted. The allowlist is deliberately small and
 * method-specific to avoid turning an endpoint naming convention into a bypass.
 * Completed-state reads use the listener profile's unique {@code user_id}
 * index. They intentionally remain authoritative instead of being cached, since
 * the pre-choice JWT must become usable immediately after the selection.</p>
 */
@Component
@RequiredArgsConstructor
public class ListenerProfileChoiceGate {

	private static final String LISTENER_PROFILE_BASE = "/api/v1/user/listener-profiles";
	private static final String OWNER_PROFILE = LISTENER_PROFILE_BASE + "/me";
	private static final String AVATAR = OWNER_PROFILE + "/avatar";
	private static final String VISIBILITY = OWNER_PROFILE + "/visibility";
	private static final Set<String> AUTH_POST_ENDPOINTS = Set.of(
			"/api/v1/auth/login",
			"/api/v1/auth/register",
			"/api/v1/auth/verify-code",
			"/api/v1/auth/resend-code",
			"/api/v1/auth/google-sign-in",
			"/api/v1/auth/complete-google-profile",
			"/api/v1/auth/username-availability",
			"/api/v1/auth/password-reset/account",
			"/api/v1/auth/forgot-password",
			"/api/v1/auth/reset-password"
	);

	private final ListenerProfileChoiceStatusReader choiceStatusReader;

	public boolean shouldReject(HttpServletRequest request, Authentication authentication) {
		if (!isAuthenticatedListener(authentication) || isOnboardingRequest(request)) {
			return false;
		}
		return choiceStatusReader.requiresChoice(
				((UserDetailsImpl) authentication.getPrincipal()).getUser()
		);
	}

	private boolean isAuthenticatedListener(Authentication authentication) {
		return authentication != null
				&& authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof UserDetailsImpl
				&& authentication.getAuthorities().stream()
						.anyMatch(authority -> "ROLE_LISTENER".equals(authority.getAuthority()));
	}

	private boolean isOnboardingRequest(HttpServletRequest request) {
		String method = request.getMethod();
		String path = applicationPath(request);
		return HttpMethod.OPTIONS.matches(method)
				|| "/error".equals(path)
				|| (HttpMethod.POST.matches(method) && AUTH_POST_ENDPOINTS.contains(path))
				|| (HttpMethod.GET.matches(method) && OWNER_PROFILE.equals(path))
				|| (HttpMethod.PATCH.matches(method) && AVATAR.equals(path))
				|| (HttpMethod.PATCH.matches(method) && VISIBILITY.equals(path));
	}

	private String applicationPath(HttpServletRequest request) {
		String requestUri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
			return requestUri.substring(contextPath.length());
		}
		return requestUri;
	}
}
