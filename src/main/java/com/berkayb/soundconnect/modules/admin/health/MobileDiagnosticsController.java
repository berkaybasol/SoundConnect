package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimiter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController @RequestMapping("/api/v1/diagnostics/mobile")
public class MobileDiagnosticsController {
    static final int MAX_BODY_BYTES = 12288;
    private final MobileDiagnosticsStore store;
    private final MobileDiagnosticsRateGuard rate;
    private final MobileDiagnosticsProperties properties;
    private final Validator validator;
    private final ObjectReader reader;

    public MobileDiagnosticsController(MobileDiagnosticsStore store, MobileDiagnosticsRateGuard rate,
                                       MobileDiagnosticsProperties properties, Validator validator, ObjectMapper mapper) {
        this.store = store; this.rate = rate; this.properties = properties; this.validator = validator;
        reader = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readerFor(MobileDiagnosticRequest.class);
    }

    @PostMapping(consumes = "application/json") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<MobileDiagnosticsStore.Receipt>> record(
            @AuthenticationPrincipal UserDetailsImpl principal, HttpServletRequest http) {
        if (principal == null || !principal.isEnabled()) return response(401, "SESSION_REQUIRED", null, null);
        if (!properties.isEnabled()) return response(503, "DIAGNOSTICS_DISABLED", null, 60L);
        var decision = rate.check(principal.getId(), http);
        if (!decision.allowed()) return response(decision.status() == AuthRateLimiter.Status.LIMITED ? 429 : 503,
                "DIAGNOSTICS_RETRY_LATER", null, decision.retryAfterSeconds());
        if (http.getContentLengthLong() > MAX_BODY_BYTES) return response(413, "DIAGNOSTICS_BODY_TOO_LARGE", null, null);
        final MobileDiagnosticRequest request;
        try {
            byte[] body = http.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) return response(413, "DIAGNOSTICS_BODY_TOO_LARGE", null, null);
            request = reader.readValue(body);
            if (request == null || !validator.validate(request).isEmpty() || request.eventId().version() != 4
                    || !properties.getEnvironment().equals(request.environment().name()))
                return response(400, "DIAGNOSTICS_INVALID", null, null);
        } catch (IOException | RuntimeException invalid) { return response(400, "DIAGNOSTICS_INVALID", null, null); }
        try {
            return response(202, "DIAGNOSTICS_ACCEPTED", store.record(principal.getId(), principal.getUser().getSessionVersion(), request), null);
        } catch (MobileDiagnosticsStore.Rejected rejected) {
            return switch (rejected.reason) {
                case SESSION -> response(401, "SESSION_REQUIRED", null, null);
                case CONFLICT -> response(409, "DIAGNOSTICS_EVENT_CONFLICT", null, null);
                case CAPACITY -> response(503, "DIAGNOSTICS_RETRY_LATER", null, 60L);
            };
        } catch (RuntimeException unavailable) { return response(503, "DIAGNOSTICS_RETRY_LATER", null, 5L); }
    }

    private static ResponseEntity<BaseResponse<MobileDiagnosticsStore.Receipt>> response(int status, String message,
            MobileDiagnosticsStore.Receipt receipt, Long retryAfter) {
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (retryAfter != null) builder.header("Retry-After", Long.toString(retryAfter));
        return builder.body(BaseResponse.<MobileDiagnosticsStore.Receipt>builder().success(status == 202)
                .code(status).message(message).data(receipt).build());
    }
}
