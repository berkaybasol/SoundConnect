package com.berkayb.soundconnect.modules.venue.suggestion;

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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public JSON boundary without a database, mail provider, Redis or an application server. */
class VenueSuggestionHttpBoundaryTest {
    private static final String PATH = "/api/v1/venue-suggestions";
    private static final String REQUEST_ID = "fc4ee31b-bfe8-4ea1-9857-1b2d7e763e92";
    private static final String CITY_ID = "25b5a6cc-cb81-4914-aa49-e2cdffb03cf8";
    private static final String DISTRICT_ID = "9d53f16a-ce8e-4c1e-899d-36b1a91db747";
    private static final ObjectMapper JSON = new ObjectMapper();

    private VenueSuggestionService service;
    private VenueSuggestionRateGuard guard;
    private VenueSuggestionSubmissionFilter filter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(VenueSuggestionService.class);
        guard = mock(VenueSuggestionRateGuard.class);
        // The endpoint must stay strict even when the application mapper ignores unknown fields.
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        filter = new VenueSuggestionSubmissionFilter(guard, new SecurityErrorResponseWriter(mapper));
        mvc = MockMvcBuilders.standaloneSetup(new VenueSuggestionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(filter)
                .build();
    }

    @ParameterizedTest
    @EnumSource(VenueSuggestionRequest.LiveMusic.class)
    void acceptsExactPayloadAndReturnsNoStore202(VenueSuggestionRequest.LiveMusic liveMusic) throws Exception {
        Map<String, Object> payload = payload();
        payload.put("liveMusic", liveMusic.name());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(payload)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));

        verify(service).accept(new VenueSuggestionRequest(UUID.fromString(REQUEST_ID), "Çığlık Sahne",
                UUID.fromString(CITY_ID), UUID.fromString(DISTRICT_ID), liveMusic));
        verify(guard).check(any(HttpServletRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidJson")
    void rejectsMalformedOrCoercedJsonBeforeService(String description, String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
        // Invalid submissions also consume abuse quota, so parser errors cannot provide free traffic.
        verify(guard).check(any(HttpServletRequest.class));
    }

    static Stream<Arguments> invalidJson() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        for (String field : payload().keySet()) {
            Map<String, Object> missing = payload();
            missing.remove(field);
            cases.add(Arguments.of("missing " + field, JSON.writeValueAsString(missing)));
            Map<String, Object> nullValue = payload();
            nullValue.put(field, null);
            cases.add(Arguments.of("null " + field, JSON.writeValueAsString(nullValue)));
            for (Object value : List.of(0, true, List.of("value"), Map.of("value", "nested"))) {
                Map<String, Object> wrongType = payload();
                wrongType.put(field, value);
                cases.add(Arguments.of("non-text " + field + " = " + value, JSON.writeValueAsString(wrongType)));
            }
        }
        for (String field : List.of("requestId", "cityId", "districtId")) {
            for (String invalid : List.of("not-a-uuid", "1-1-1-1-1", " " + REQUEST_ID, REQUEST_ID + " ")) {
                Map<String, Object> wrongUuid = payload();
                wrongUuid.put(field, invalid);
                cases.add(Arguments.of("noncanonical " + field + " = " + invalid, JSON.writeValueAsString(wrongUuid)));
            }
        }
        for (String invalid : List.of("0", "yes", "MAYBE", "")) {
            Map<String, Object> wrongEnum = payload();
            wrongEnum.put("liveMusic", invalid);
            cases.add(Arguments.of("invalid enum " + invalid, JSON.writeValueAsString(wrongEnum)));
        }
        for (String field : List.of("recipient", "recipients", "email", "instagram", "unknown")) {
            Map<String, Object> extra = payload();
            extra.put(field, "private-contact@example.invalid");
            cases.add(Arguments.of("unexpected field " + field, JSON.writeValueAsString(extra)));
        }
        Map<String, Object> tooLong = payload();
        tooLong.put("venueName", "a".repeat(401));
        cases.add(Arguments.of("raw name validation bound", JSON.writeValueAsString(tooLong)));
        for (String shape : List.of("[]", "true", "123", "null", "\"text\"", "{}", "{", "")) {
            cases.add(Arguments.of("invalid root or syntax " + shape, shape));
        }
        return cases.stream();
    }

    @Test
    void queryParametersCannotOverrideJsonIdentityNameOrMailRecipient() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .queryParam("venueName", "Injected name")
                        .queryParam("cityId", REQUEST_ID)
                        .queryParam("liveMusic", "NO")
                        .queryParam("recipient", "attacker@example.invalid")
                        .content(JSON.writeValueAsBytes(payload())))
                .andExpect(status().isAccepted());

        verify(service).accept(new VenueSuggestionRequest(UUID.fromString(REQUEST_ID), "Çığlık Sahne",
                UUID.fromString(CITY_ID), UUID.fromString(DISTRICT_ID), VenueSuggestionRequest.LiveMusic.YES));
    }

    @Test
    void rejectsOtherContentTypesInsteadOfBindingFormParameters() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("venueName", "A venue").param("liveMusic", "YES"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("guardedPaths")
    void appliesGuardToCanonicalEncodedContextAndMatrixPaths(String contextPath, String uri) throws Exception {
        MockHttpServletRequest request = request("POST", uri);
        request.setContextPath(contextPath);
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> continued.set(true));

        verify(guard).check(request);
        assertThat(continued).isTrue();
    }

    static Stream<Arguments> guardedPaths() {
        return Stream.of(
                Arguments.of("", PATH),
                Arguments.of("", "/api/v1/%76enue-suggestions"),
                Arguments.of("", "/api/v1/venue-%73uggestions"),
                Arguments.of("", PATH + ";client=guest"),
                Arguments.of("/soundconnect", "/soundconnect" + PATH),
                Arguments.of("/soundconnect", "/soundconnect/api/v1/%76enue-suggestions")
        );
    }

    @ParameterizedTest
    @MethodSource("unguardedRequests")
    void doesNotApplyPublicPostGuardToOtherRoutesOrMethods(String method, String uri) throws Exception {
        MockHttpServletRequest request = request(method, uri);
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (original, response) -> {
            assertThat(original).isSameAs(request);
            continued.set(true);
        });

        assertThat(continued).isTrue();
        verifyNoInteractions(guard);
    }

    static Stream<Arguments> unguardedRequests() {
        return Stream.of(Arguments.of("GET", PATH), Arguments.of("HEAD", PATH), Arguments.of("OPTIONS", PATH),
                Arguments.of("PUT", PATH), Arguments.of("PATCH", PATH), Arguments.of("DELETE", PATH),
                Arguments.of("POST", PATH + "/"), Arguments.of("POST", PATH + "/123"),
                Arguments.of("POST", PATH + "-other"), Arguments.of("POST", "/other" + PATH));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4095, 4096})
    void allowsBodyAtOrBelowLimitAndReplaysExactBytes(int size) throws Exception {
        MockHttpServletRequest request = request("POST", PATH);
        byte[] content = "a".repeat(size).getBytes(StandardCharsets.UTF_8);
        request.setContent(content);
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(content);
            assertThat(wrapped.getContentLength()).isEqualTo(size);
            continued.set(true);
        });

        assertThat(continued).isTrue();
    }

    @Test
    void replaysTurkishUtf8ViaInputStreamAndReader() throws Exception {
        MockHttpServletRequest request = request("POST", PATH);
        String content = "{\"venueName\":\"Çığlık Şişli 🌱\"}";
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(content);
            assertThat(wrapped.getReader().readLine()).isEqualTo(content);
            continued.set(true);
        });

        assertThat(continued).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {4097, 1048576})
    void rejectsOversizedBodyWithoutReadingBeyond4097BytesEvenWithoutContentLength(int bodySize) throws Exception {
        CountingInputStream input = new CountingInputStream(bodySize);
        MockHttpServletRequest request = streamingRequest(input);
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, downstream) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(input.readCount).isEqualTo(4097);
        assertThat(continued).isFalse();
        verify(guard).check(request);
    }

    @Test
    void rateLimitReturns429AndRetryAfterBeforeReadingRequestBody() throws Exception {
        doThrow(new RateLimitedException(ErrorType.VENUE_SUGGESTION_RATE_LIMITED, 73)).when(guard).check(any());
        assertGuardRejection(429, "73", ErrorType.VENUE_SUGGESTION_RATE_LIMITED);
    }

    @Test
    void unavailableAbuseProtectionReturns503AndRetryAfterBeforeReadingRequestBody() throws Exception {
        doThrow(new ServiceUnavailableRetryException(ErrorType.VENUE_SUGGESTION_UNAVAILABLE, 30))
                .when(guard).check(any());
        assertGuardRejection(503, "30", ErrorType.VENUE_SUGGESTION_UNAVAILABLE);
    }

    private void assertGuardRejection(int expectedStatus, String retryAfter, ErrorType errorType) throws Exception {
        CountingInputStream input = new CountingInputStream(1_000_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(streamingRequest(input), response, (wrapped, downstream) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(JSON.readTree(response.getContentAsByteArray()).get("code").asInt()).isEqualTo(errorType.getCode());
        assertThat(input.readCount).isZero();
        assertThat(continued).isFalse();
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setRemoteAddr("203.0.113.21");
        return request;
    }

    private static MockHttpServletRequest streamingRequest(CountingInputStream input) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    private static Map<String, Object> payload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", REQUEST_ID);
        result.put("venueName", "Çığlık Sahne");
        result.put("cityId", CITY_ID);
        result.put("districtId", DISTRICT_ID);
        result.put("liveMusic", "YES");
        return result;
    }

    private static final class CountingInputStream extends ServletInputStream {
        private final int length;
        private int readCount;
        private CountingInputStream(int length) { this.length = length; }
        @Override public int read() {
            if (readCount >= length) return -1;
            readCount++;
            return 'a';
        }
        @Override public boolean isFinished() { return readCount == length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }
}
