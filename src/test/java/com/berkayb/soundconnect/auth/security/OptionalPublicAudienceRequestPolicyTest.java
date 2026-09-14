package com.berkayb.soundconnect.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalPublicAudienceRequestPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/public/search/profiles", "/api/v1/profiles/MUSICIAN/profile-id/media",
            "/api/v1/public/media/owner/BAND/band-id", "/api/v1/public/media/owner/USER/id/kind/AUDIO",
            "/api/v1/public/musician-profiles/profile-id", "/api/v1/public/musician-profiles/search",
            "/api/v1/public/bands/band-id", "/api/v1/public/venue-profiles/venue-id",
            "/api/v1/public/studio-profiles/studio-id", "/api/v1/public/studio-profiles/studio-id/rooms/room-id/availability",
            "/api/v1/public/studio-profiles/studio-id/equipment/item-id/availability",
            "/api/v1/promotions/displayable/VENUE_MANAGEMENT_PANEL"
    })
    void suppliedBearerCannotFallBackToGuestOnAudienceSources(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer stale-token");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(request)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/events/event-id", "/api/v1/venues/venue-id", "/api/v1/cities",
            "/api/v1/spotify/search/tracks", "/api/v1/public/unrelated", "/api/v1/public/search/other",
            "/api/v1/public/media-admin", "/api/v1/public/studio-profiles-similar/profile-id"
    })
    void unrelatedPublicPathsRemainOutsideThisPolicy(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer stale-token");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(request)).isFalse();
    }

    @Test void realGuestOtherAuthenticationSchemesAndNonGetRequestsRemainUnchanged() {
        var guest = new MockHttpServletRequest("GET", "/api/v1/public/search/profiles");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(guest)).isFalse();
        guest.addHeader("Authorization", "Basic encoded");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(guest)).isFalse();
        var post = new MockHttpServletRequest("POST", "/api/v1/public/search/profiles");
        post.addHeader("Authorization", "Bearer stale-token");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(post)).isFalse();
    }

    @Test void contextPathAndEmptyBearerDoNotTurnAnIntendedSessionIntoGuest() {
        var request = new MockHttpServletRequest("GET", "/soundconnect/api/v1/public/search/profiles");
        request.setContextPath("/soundconnect");
        request.addHeader("Authorization", "Bearer ");
        assertThat(OptionalPublicAudienceRequestPolicy.requiresAuthenticatedBearer(request)).isTrue();
    }
}
