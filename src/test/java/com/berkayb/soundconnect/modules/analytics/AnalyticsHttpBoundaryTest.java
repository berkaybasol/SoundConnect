package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real scoped JSON parser/body guard with no network, database or application server. */
class AnalyticsHttpBoundaryTest {
    private static final String PATH = "/api/v1/analytics/observations";
    private static final String CLIENT = "10000000-0000-4000-8000-000000000001";
    private static final String EVENT = "20000000-0000-4000-8000-000000000001";
    private static final String VENUE = "30000000-0000-4000-8000-000000000001";
    private static final String OBSERVATION = "40000000-0000-4000-8000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private AnalyticsService service;
    private AnalyticsRateGuard guard;
    private AnalyticsSubmissionFilter filter;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach void setup() {
        service = mock(AnalyticsService.class);
        guard = mock(AnalyticsRateGuard.class);
        mapper = Jackson2ObjectMapperBuilder.json().build();
        // The collector's strictness must not depend on the global default.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        filter = new AnalyticsSubmissionFilter(guard, new SecurityErrorResponseWriter(mapper));
        mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service, guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(filter).build();
    }

    @ParameterizedTest @EnumSource(AnalyticsRequest.Type.class)
    void exactPayloadMapsWithoutCoercionAndAcknowledgesOriginalIds(AnalyticsRequest.Type type) throws Exception {
        Map<String, Object> row = row();
        row.put("type", type.name());
        if (type == AnalyticsRequest.Type.VENUE_PROFILE_VIEW) {
            row.remove("eventId"); row.put("venueId", VENUE); row.put("sourceEventId", EVENT);
        }
        when(service.observe(isNull(), any())).thenReturn(new AnalyticsResponse.Acknowledgement(List.of(UUID.fromString(OBSERVATION))));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("clientId", CLIENT, "observations", List.of(row)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.acknowledgedIds[0]").value(OBSERVATION));
        verify(service).observe(isNull(), argThat(request -> request.clientId().equals(UUID.fromString(CLIENT))
                && request.observations().size() == 1 && request.observations().getFirst().type() == type
                && request.observations().getFirst().observedAt().equals(Instant.parse("2026-09-08T12:00:00Z"))));
        verify(guard).submission(any());
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidJson")
    void rejectsInvalidJsonBeforeServiceWhileStillApplyingAbuseQuota(String description, String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        verify(guard).submission(any());
    }

    static Stream<Arguments> invalidJson() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("clientId", "observations")) {
            Map<String, Object> missing = payload(); missing.remove(field);
            cases.add(Arguments.of("missing " + field, JSON.writeValueAsString(missing)));
            for (Object value : Arrays.asList(null, 123, true, "text", Map.of("nested", true))) {
                Map<String, Object> changed = payload(); changed.put(field, value);
                cases.add(Arguments.of("wrong root scalar " + field + " " + value, JSON.writeValueAsString(changed)));
            }
        }
        for (String field : List.of("id", "type", "eventId", "observedAt")) {
            Map<String, Object> missing = row(); missing.remove(field);
            cases.add(Arguments.of("missing row " + field, body(missing)));
            for (Object value : Arrays.asList(null, 123, true, List.of("text"), Map.of("nested", true))) {
                Map<String, Object> changed = row(); changed.put(field, value);
                cases.add(Arguments.of("wrong row scalar " + field + " " + value, body(changed)));
            }
        }
        for (String invalid : List.of("1-1-1-1-1", "not-a-uuid", "00000000-0000-0000-0000-000000000000", " " + EVENT, EVENT + " ")) {
            Map<String, Object> root = payload(); root.put("clientId", invalid);
            cases.add(Arguments.of("invalid client UUID " + invalid, JSON.writeValueAsString(root)));
            for (String field : List.of("id", "eventId")) {
                Map<String, Object> changed = row(); changed.put(field, invalid);
                cases.add(Arguments.of("invalid UUID " + field + " " + invalid, body(changed)));
            }
        }
        for (String invalid : List.of("event_impression", "0", "UNKNOWN", "")) {
            Map<String, Object> changed = row(); changed.put("type", invalid);
            cases.add(Arguments.of("invalid type " + invalid, body(changed)));
        }
        for (String invalid : List.of("2026-09-08T12:00:00+00:00", "2026-09-08T12:00:00", "2026-09-08", "yesterday", "")) {
            Map<String, Object> changed = row(); changed.put("observedAt", invalid);
            cases.add(Arguments.of("non-UTC timestamp " + invalid, body(changed)));
        }
        for (String field : List.of("userId", "actor", "ownerId", "count", "unknown")) {
            Map<String, Object> root = payload(); root.put(field, CLIENT);
            cases.add(Arguments.of("unknown root identity " + field, JSON.writeValueAsString(root)));
            Map<String, Object> changed = row(); changed.put(field, CLIENT);
            cases.add(Arguments.of("unknown row identity " + field, body(changed)));
        }
        for (String field : List.of("venueId", "sourceEventId")) {
            Map<String, Object> changed = row(); changed.put(field, VENUE);
            cases.add(Arguments.of("event contains invalid context " + field, body(changed)));
        }
        Map<String, Object> missingVenue = row(); missingVenue.put("type", "VENUE_PROFILE_VIEW");
        cases.add(Arguments.of("venue type without venue", body(missingVenue)));
        missingVenue.put("venueId", VENUE);
        cases.add(Arguments.of("venue type retains event ID", body(missingVenue)));
        for (List<?> observations : List.of(List.of(), Collections.nCopies(21, row()), List.of(row(), row()), List.of("text"), List.of(123))) {
            cases.add(Arguments.of("invalid batch " + observations.size(), JSON.writeValueAsString(Map.of("clientId", CLIENT, "observations", observations))));
        }
        String valid = JSON.writeValueAsString(payload());
        cases.add(Arguments.of("duplicate root clientId", valid.replace("\"clientId\":", "\"clientId\":\"" + VENUE + "\",\"clientId\":")));
        cases.add(Arguments.of("duplicate observation id", valid.replace("\"id\":", "\"id\":\"" + VENUE + "\",\"id\":")));
        cases.add(Arguments.of("duplicate observation type", valid.replace("\"type\":", "\"type\":\"EVENT_DETAIL_VIEW\",\"type\":")));
        for (String shape : List.of("[]", "null", "true", "123", "\"text\"", "{}", "{", "")) {
            cases.add(Arguments.of("invalid root " + shape, shape));
        }
        return cases.stream();
    }

    @Test void acceptsMaximumTwentyUniqueObservations() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) { Map<String, Object> row = row(); row.put("id", UUID.randomUUID().toString()); rows.add(row); }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("clientId", CLIENT, "observations", rows))))
                .andExpect(status().isOk());
        verify(service).observe(isNull(), argThat(request -> request.observations().size() == 20));
    }

    @Test void rejectsFormBindingAndTrailingSlashCannotBypassTheExactEndpoint() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("clientId", CLIENT))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(PATH + "/").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(payload())))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(ints = {0, 16383, 16384})
    void bodyAtOrBelowLimitIsReplayedByteForByte(int size) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        byte[] content = "a".repeat(size).getBytes(StandardCharsets.UTF_8); request.setContent(content);
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(content);
            continued.set(true);
        });
        assertThat(continued).isTrue();
    }

    @ParameterizedTest @ValueSource(ints = {16385, 1048576})
    void unknownLengthChunkedBodiesStopReadingAt16385Bytes(int length) throws Exception {
        CountingInputStream input = new CountingInputStream(length);
        MockHttpServletRequest request = streamingRequest(input);
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, response, (wrapped, downstream) -> continued.set(true));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(JSON.readTree(response.getContentAsByteArray()).get("code").asInt()).isEqualTo(9914);
        assertThat(input.readCount).isEqualTo(16385);
        assertThat(continued).isFalse();
        verify(guard).submission(request);
    }

    @Test void declaredOversizedBodyCannotReachJsonOrService() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(9914));
        verifyNoInteractions(service);
    }

    @ParameterizedTest @MethodSource("guardedPaths")
    void decodedContextAndMatrixPathsStillApplyTheGuard(String context, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path); request.setContextPath(context);
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> continued.set(true));
        verify(guard).submission(request); assertThat(continued).isTrue();
    }
    static Stream<Arguments> guardedPaths() { return Stream.of(
            Arguments.of("", PATH), Arguments.of("", "/api/v1/analytics/%6Fbservations"),
            Arguments.of("", PATH + ";client=guest"), Arguments.of("/soundconnect", "/soundconnect" + PATH)); }

    @Test void transientGuardFailureHasRetryAfterAndNeverReadsLargeBody() throws Exception {
        for (boolean limited : List.of(true, false)) {
            reset(guard);
            RuntimeException failure = limited ? new RateLimitedException(ErrorType.ANALYTICS_RATE_LIMITED, 73)
                    : new ServiceUnavailableRetryException(ErrorType.ANALYTICS_UNAVAILABLE, 30);
            doThrow(failure).when(guard).submission(any());
            CountingInputStream input = new CountingInputStream(1_000_000);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(streamingRequest(input), response, (request, downstream) -> { throw new AssertionError("Guard bypassed"); });
            assertThat(response.getStatus()).isEqualTo(limited ? 429 : 503);
            assertThat(response.getHeader("Retry-After")).isEqualTo(limited ? "73" : "30");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(input.readCount).isZero();
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1, 6, 8, 29, 31, 89, 91, Integer.MAX_VALUE})
    void unsupportedReportingWindowIsRejectedByRealServiceBeforeDatabase(int days) throws Exception {
        AnalyticsStore store = mock(AnalyticsStore.class);
        EventScheduleClock clock = mock(EventScheduleClock.class);
        AnalyticsProperties properties = new AnalyticsProperties(); properties.setEnabled(true); properties.setReportingEnabled(true);
        properties.setHmacSecret("test-only-analytics-http-boundary-secret");
        AnalyticsService realService = new AnalyticsService(store, new AnalyticsIdentity(properties), guard, clock);
        ownerMvc(realService).perform(get("/api/v1/venue-analytics/" + VENUE).param("days", Integer.toString(days)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(9910));
        verifyNoInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings={"","/events","/events/20000000-0000-4000-8000-000000000001"})
    void reportingDefaultsOffForEveryPrivateEndpointBeforeRedisOrDatabase(String suffix) throws Exception {
        AnalyticsStore store=mock(AnalyticsStore.class);
        EventScheduleClock clock=mock(EventScheduleClock.class);
        AnalyticsProperties properties=new AnalyticsProperties(); properties.setEnabled(true);
        properties.setHmacSecret("test-only-analytics-http-boundary-secret");
        var realService=new AnalyticsService(store,new AnalyticsIdentity(properties),guard,clock);
        ownerMvc(realService).perform(get("/api/v1/venue-analytics/"+VENUE+suffix))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(9913))
                .andExpect(header().string("Retry-After","30")).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(store,guard,clock);
    }

    @Test void collectorStillAcknowledgesWhileReportingIsOff() throws Exception {
        AnalyticsStore store=mock(AnalyticsStore.class);
        EventScheduleClock clock=mock(EventScheduleClock.class); Instant at=Instant.parse("2026-09-08T12:00:00Z");
        when(clock.instant()).thenReturn(at);
        AnalyticsProperties properties=new AnalyticsProperties(); properties.setEnabled(true);
        properties.setHmacSecret("test-only-analytics-http-boundary-secret");
        var realService=new AnalyticsService(store,new AnalyticsIdentity(properties),guard,clock);
        ownerMvc(realService).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(payload())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.acknowledgedIds[0]").value(OBSERVATION));
        verify(store).observe(eq(UUID.fromString(CLIENT)),argThat(batch -> batch.observations().getFirst().id().equals(UUID.fromString(OBSERVATION))),eq(at));
        verify(guard).submission(any()); verify(guard).observations(UUID.fromString(CLIENT),UUID.fromString(CLIENT),1);
        verifyNoMoreInteractions(store,guard);
    }

    private MockMvc ownerMvc(AnalyticsService realService) {
        User user = User.builder().username("owner").build(); user.setId(UUID.fromString(CLIENT));
        UserDetailsImpl principal = new UserDetailsImpl(user);
        return MockMvcBuilders.standaloneSetup(new AnalyticsController(realService, guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == UserDetailsImpl.class; }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer model,
                            NativeWebRequest request, WebDataBinderFactory binderFactory) { return principal; }
                }).addFilters(filter).build();
    }

    private static Map<String, Object> payload() { return new LinkedHashMap<>(Map.of("clientId", CLIENT, "observations", List.of(row()))); }
    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("id", OBSERVATION); row.put("type", "EVENT_IMPRESSION");
        row.put("eventId", EVENT); row.put("observedAt", "2026-09-08T12:00:00Z"); return row;
    }
    private static String body(Map<String, Object> row) throws Exception { return JSON.writeValueAsString(Map.of("clientId", CLIENT, "observations", List.of(row))); }
    private static MockHttpServletRequest streamingRequest(CountingInputStream input) {
        return new MockHttpServletRequest("POST", PATH) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
    }
    private static final class CountingInputStream extends ServletInputStream {
        private final int length; private int readCount;
        private CountingInputStream(int length) { this.length = length; }
        @Override public int read() { if (readCount >= length) return -1; readCount++; return 'a'; }
        @Override public boolean isFinished() { return readCount == length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }
}
