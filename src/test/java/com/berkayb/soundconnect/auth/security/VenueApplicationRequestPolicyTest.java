package com.berkayb.soundconnect.auth.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VenueApplicationRequestPolicyTest {
    private static final UUID APPLICATION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String ID = "00000000-0000-4000-8000-000000000002";
    private static final String BASE = VenueApplicationRequestPolicy.BASE + APPLICATION;

    @ParameterizedTest
    @CsvSource({"GET,''", "POST,/promote", "GET,/notifications/{id}",
            "POST,/notifications/{id}/read", "POST,/notifications/delivery-state", "PUT,/push/devices/{id}",
            "DELETE,/push/devices/{id}?clientRevision=1"})
    void exactSupportedMethodsAndPathsOnly(String method, String suffix) {
        assertThat(VenueApplicationRequestPolicy.allows(request(method, BASE + suffix.replace("{id}", ID)), APPLICATION)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/promote/", "/../other", "?x=1", "?", "/notifications", "/notifications/read-all",
            "/notifications/{id}/read?x=1", "/push/devices/{id}?clientRevision=1&clientRevision=2",
            "/push/devices/{id}?clientRevision=0", "/push/devices/{id}?clientRevision=-1",
            "/push/devices/{id}?clientRevision=9223372036854775808", "/push/devices/{id}?clientRevision=01",
            "/push/devices/{id}?clientRevision=%31", "/push/devices/{id}?clientRevision=1&x=1",
            "/push/devices/{id}", "/notifications/1-1-1-1-1", "/notifications/{id};x=1", "/notifications/%2e%2e"})
    void malformedSuffixAndQueryFailClosed(String suffix) {
        String path = BASE + suffix.replace("{id}", ID);
        for (String method : new String[]{"GET", "POST", "PATCH", "HEAD", "OPTIONS", "DELETE"}) {
            assertThat(VenueApplicationRequestPolicy.allows(request(method, path), APPLICATION)).as(method + " " + suffix).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/user/notifications", "/api/v1/user/dm", "/ws", "/api/v1/auth/login",
            "/api/v1/venue-application-session/applications/00000000-0000-4000-8000-000000000002",
            "/prefix/api/v1/venue-application-session/applications/00000000-0000-4000-8000-000000000001"})
    void unrelatedOrOtherApplicationCannotUseScope(String path) {
        assertThat(VenueApplicationRequestPolicy.allows(request("GET", path), APPLICATION)).isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        int query = path.indexOf('?');
        var request = new MockHttpServletRequest(method, query < 0 ? path : path.substring(0, query));
        if (query >= 0) request.setQueryString(path.substring(query + 1));
        return request;
    }
}
