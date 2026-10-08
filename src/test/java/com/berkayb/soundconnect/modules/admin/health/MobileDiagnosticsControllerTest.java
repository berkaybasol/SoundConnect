package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimiter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {MobileDiagnosticsController.class, MobileDiagnosticsAdminController.class,
        MobileDiagnosticsProperties.class, SystemHealthControllerSecurityTest.Security.class, GlobalExceptionHandler.class})
class MobileDiagnosticsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired jakarta.validation.Validator validator;
    @MockitoBean MobileDiagnosticsStore store;
    @MockitoBean MobileDiagnosticsRateGuard rate;
    UUID actor = UUID.randomUUID();
    UsernamePasswordAuthenticationToken auth;
    @BeforeEach void setup() {
        // ApplicationReadyEvent invokes the mocked lifecycle method before test requests.
        // Keep no-interaction assertions scoped to the HTTP operation under test.
        clearInvocations(store);
        var principal = mock(UserDetailsImpl.class); var user = mock(User.class);
        when(principal.getId()).thenReturn(actor); when(principal.isEnabled()).thenReturn(true);
        when(principal.getUser()).thenReturn(user); when(user.getSessionVersion()).thenReturn(7L);
        auth = new UsernamePasswordAuthenticationToken(principal, "unused", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(rate.check(any(), any())).thenReturn(AuthRateLimiter.Decision.permit());
        when(store.record(any(), anyLong(), any())).thenAnswer(invocation -> {
            MobileDiagnosticRequest body = invocation.getArgument(2);
            return new MobileDiagnosticsStore.Receipt(body.eventId(), true);
        });
    }

    @Test void anonymousCannotSubmitAndOrdinaryAccountCannotReadOtherEvents() throws Exception {
        mvc.perform(post("/api/v1/diagnostics/mobile").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/system-health/mobile-events").with(authentication(auth))).andExpect(status().isForbidden());
        verifyNoInteractions(rate, store);
    }

    @Test void validStrictReceiptIncludesExactIdAndUsesAuthenticatedVersion() throws Exception {
        Map<String, Object> body = payload();
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.accepted").value(true)).andExpect(jsonPath("$.data.eventId").value(body.get("eventId").toString()));
        verify(store).record(eq(actor), eq(7L), any());
    }

    @Test void unknownPrivateFieldsEnumsUnsafeFramesAndExtraJsonAreRejectedWithoutEcho() throws Exception {
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, Object> entry : Map.<String, Object>of(
                "message", "private-token@example.invalid", "accountId", actor.toString(), "errorType", "private-token@example.invalid",
                "source", 0, "environment", "production", "frames", List.of("http://private-token@example.invalid"),
                "severity", "DEBUG").entrySet()) {
            Map<String, Object> body = payload(); body.put(entry.getKey(), entry.getValue()); invalid.add(json.writeValueAsString(body));
        }
        Map<String, Object> tooMany = payload(); tooMany.put("frames", java.util.Collections.nCopies(41, "dart:async/future_impl.dart:1"));
        invalid.add(json.writeValueAsString(tooMany));
        invalid.add(json.writeValueAsString(payload()) + " {}");
        invalid.add(json.writeValueAsString(payload()).replace("\"ERROR\"", "\"ERROR\",\"severity\":\"FATAL\""));
        for (String body : invalid) {
            mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-token"))));
        }
        verifyNoInteractions(store);
    }

    @Test void bodyLimitAndRateGuardRejectBeforePersistence() throws Exception {
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(MobileDiagnosticsController.MAX_BODY_BYTES + 1))).andExpect(status().isPayloadTooLarge());
        when(rate.check(any(), any())).thenReturn(AuthRateLimiter.Decision.block(17));
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload()))).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"));
        when(rate.check(any(), any())).thenReturn(AuthRateLimiter.Decision.unavailable());
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload()))).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"));
        verifyNoInteractions(store);
    }

    @Test void chunkedBodyIsReadOnlyThroughTheHardByteLimit() {
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var http = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override public int read() { consumed.incrementAndGet(); return ' '; }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
                };
            }
        };
        var controller = new MobileDiagnosticsController(store, rate, new MobileDiagnosticsProperties(), validator, json);
        var result = controller.record((UserDetailsImpl) auth.getPrincipal(), http);
        org.assertj.core.api.Assertions.assertThat(result.getStatusCode().value()).isEqualTo(413);
        org.assertj.core.api.Assertions.assertThat(consumed.get()).isEqualTo(MobileDiagnosticsController.MAX_BODY_BYTES + 1);
        verifyNoInteractions(store);
    }

    @Test void sessionRevocationDuringRequestAndStoreFailuresNeverReturnAccepted() throws Exception {
        doThrow(new MobileDiagnosticsStore.Rejected(MobileDiagnosticsStore.Rejection.SESSION)).when(store).record(any(), anyLong(), any());
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload()))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        doThrow(new IllegalStateException("private-database-credential")).when(store).record(any(), anyLong(), any());
        mvc.perform(post("/api/v1/diagnostics/mobile").with(authentication(auth)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload()))).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-database"))));
    }

    @Test void adminRecentListIsBoundedAndReusesExistingPermission() throws Exception {
        when(store.recent(10)).thenReturn(List.of());
        mvc.perform(get("/api/v1/admin/system-health/mobile-events").with(user("admin").authorities(new SimpleGrantedAuthority("ADMIN_PANEL_ACCESS"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.events").isEmpty());
        mvc.perform(get("/api/v1/admin/system-health/mobile-events?limit=11").with(user("admin").authorities(new SimpleGrantedAuthority("ADMIN_PANEL_ACCESS"))))
                .andExpect(status().isBadRequest());
        verify(store).recent(10); verifyNoMoreInteractions(store);
    }

    private static Map<String, Object> payload() {
        return new LinkedHashMap<>(Map.of("eventId", UUID.randomUUID().toString(), "severity", "ERROR",
                "source", "DIAGNOSTICS_CHECK", "errorType", "DiagnosticAcceptanceCheck",
                "environment", "local", "frames", List.of("package:soundconnect_23_12_25codx/core/app_diagnostics.dart:15:3")));
    }
}
