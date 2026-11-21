package com.berkayb.soundconnect.shared.telemetry.tracing;

import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SafeHttpObservationHandlerTest {
    private static final String USER = "private-user-739bc95b";
    private static final String SECRET = "super-secret-token-553911";
    private static final String EMAIL = "sensitive-person@example.invalid";
    private static final String REMOTE_TRACE = "11223344556677889900aabbccddeeff";

    @Test void disabledByDefaultDoesNotCreateExporters() {
        new WebApplicationContextRunner().withUserConfiguration(SafeHttpTelemetryConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(SafeHttpObservationHandler.class));
    }

    @Test void actualOtlpPayloadCorrelatesHandledErrorWithoutSendingSensitiveInputs() throws Exception {
        try (var server = collector(); var telemetry = telemetry(server, 1)) {
            MockMvc mvc = mvc(telemetry);
            mvc.perform(post("/fixture/users/" + USER).queryParam("email", EMAIL)
                            .header("Authorization", "Bearer " + SECRET)
                            .header("Cookie", "session=" + SECRET)
                            .header("traceparent", "00-" + REMOTE_TRACE + "-1122334455667788-01")
                            .header("tracestate", "account=" + USER)
                            .header("baggage", "email=" + EMAIL)
                            .contentType("text/plain").content("password=" + SECRET))
                    .andExpect(status().isInternalServerError());
            telemetry.flush();
            Map<String, byte[]> wire = twoPayloads(server);
            assertThat(wire).containsOnlyKeys("/v1/traces", "/v1/logs");
            for (byte[] payload : wire.values()) {
                String text = new String(payload, StandardCharsets.UTF_8);
                assertThat(text).contains("soundconnect-api", "POST", "/fixture/users/{id}", "SERVER_ERROR",
                                "java.lang.IllegalStateException", "SafeHttpObservationHandlerTest.java")
                        .doesNotContain(USER, SECRET, EMAIL, "SELECT", "password", "Bearer", "Cookie", "baggage",
                                "tracestate", "url.full", "exception.message", "exception.stacktrace", "process.command",
                                "host.name", "user.id");
                assertThat(HexFormat.of().formatHex(payload)).doesNotContain(REMOTE_TRACE);
            }
            byte[] span = message(wire.get("/v1/traces"), 1, 2, 2);
            byte[] log = message(wire.get("/v1/logs"), 1, 2, 2);
            assertThat(message(span, 1)).hasSize(16).isEqualTo(message(log, 9));
            assertThat(message(span, 2)).hasSize(8).isEqualTo(message(log, 10));
            assertThat(message(span, 4)).isEmpty(); // never inherits untrusted remote parent
            assertThat(message(span, 11)).isEmpty(); // no exception events or stack text
            assertThat(message(span, 13)).isEmpty(); // no propagated links
        }
    }

    @Test void unknownRoutesAndMethodsCannotTurnIntoRawInputAttributes() throws Exception {
        try (var server = collector(); var telemetry = telemetry(server, 1)) {
            var request = new MockHttpServletRequest(SECRET, "/private/" + USER);
            request.setQueryString("email=" + EMAIL);
            var response = new MockHttpServletResponse();
            response.setStatus(404);
            var context = new ServerRequestObservationContext(request, response);
            context.setPathPattern("/query?secret=" + SECRET);
            telemetry.onStart(context);
            telemetry.onStop(context);
            telemetry.flush();
            for (byte[] payload : twoPayloads(server).values()) {
                assertThat(new String(payload, StandardCharsets.UTF_8)).contains("OTHER", "UNKNOWN", "CLIENT_ERROR")
                        .doesNotContain(USER, SECRET, EMAIL);
            }
        }
    }

    @Test void zeroSamplingExportsNeitherSpansNorErrorLogs() throws Exception {
        try (var server = collector(); var telemetry = telemetry(server, 0)) {
            mvc(telemetry).perform(post("/fixture/users/" + USER).contentType("text/plain").content(SECRET))
                    .andExpect(status().isInternalServerError());
            telemetry.flush();
            assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test void successfulRequestExportsOneSpanAndNoErrorLog() throws Exception {
        try (var server = collector(); var telemetry = telemetry(server, 1)) {
            mvc(telemetry).perform(post("/fixture/success")).andExpect(status().isOk());
            telemetry.flush();
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/traces");
            assertThat(request.getBody().readUtf8()).contains("SUCCESS", "/fixture/success")
                    .doesNotContain("exception.type");
            assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test void stalledCollectorAndQueueSaturationDoNotBlockRequests() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                }
            });
            server.start();
            try (var telemetry = telemetry(server, 1)) {
                // Substantially exceed both bounded queues while their network workers stall.
                assertTimeout(Duration.ofSeconds(2), () -> {
                    for (int i = 0; i < SafeHttpObservationHandler.MAX_QUEUE * 4; i++) {
                        var response = new MockHttpServletResponse();
                        response.setStatus(500);
                        var context = new ServerRequestObservationContext(new MockHttpServletRequest("POST", "/fixture"), response);
                        context.setPathPattern("/fixture");
                        telemetry.onStart(context);
                        telemetry.onStop(context);
                    }
                });
                mvc(telemetry).perform(post("/fixture/users/" + USER).contentType("text/plain").content(SECRET))
                        .andExpect(status().isInternalServerError());
            }
        }
    }

    @Test void collectorRedirectIsNotFollowed() throws Exception {
        try (var other = collector(); var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setResponseCode(302).addHeader("Location", other.url("/stolen"));
                }
            });
            server.start();
            try (var telemetry = telemetry(server, 1)) {
                mvc(telemetry).perform(post("/fixture/users/" + USER).contentType("text/plain").content(SECRET))
                        .andExpect(status().isInternalServerError());
                telemetry.flush();
                assertThat(server.getRequestCount()).isEqualTo(2);
                assertThat(other.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
            }
        }
    }

    @Test void configurationRejectsInvalidSamplingAndNonLocalOrCredentialedCollectors() {
        for (double value : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var properties = new SafeHttpTelemetryProperties();
            properties.setSampleProbability(value);
            assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalArgumentException.class);
        }
        for (String endpoint : new String[] {"https://example.com:4318", "http://127.0.0.1:4318?token=" + SECRET,
                "http://user:password@127.0.0.1:4318", "http://localhost:4318/v1/traces", "http://127.0.0.1",
                "http://127.0.0.1:4318#secret", "http://169.254.169.254:80", "http://bad-host:4318"}) {
            var properties = new SafeHttpTelemetryProperties();
            properties.setCollectorEndpoint(endpoint);
            assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(SECRET).hasMessageNotContaining("password");
        }
    }

    @Test void sourceLocationSkipsExternalPathsAndBoundsCyclicCauses() {
        var one = new RuntimeException(SECRET);
        var two = new RuntimeException(EMAIL);
        one.initCause(two);
        two.initCause(one);
        one.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.berkayb.soundconnect.Bad", "method", "C:\\private\\" + SECRET, 2)});
        two.setStackTrace(new StackTraceElement[0]);
        assertTimeout(Duration.ofMillis(100), () -> assertThat(SafeExceptionMetadata.from(one))
                .isEqualTo(new SafeExceptionMetadata("java.lang.RuntimeException", "UNKNOWN", 0)));
    }

    private static SafeHttpObservationHandler telemetry(MockWebServer server, double probability) {
        var properties = new SafeHttpTelemetryProperties();
        properties.setCollectorEndpoint("http://127.0.0.1:" + server.getPort());
        properties.setSampleProbability(probability);
        return SafeHttpObservationHandler.create(properties);
    }

    private static MockWebServer collector() throws Exception {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(200); }
        });
        server.start();
        return server;
    }

    private static MockMvc mvc(SafeHttpObservationHandler telemetry) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(telemetry);
        return MockMvcBuilders.standaloneSetup(new FixtureController()).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ServerHttpObservationFilter(registry)).build();
    }

    private static Map<String, byte[]> twoPayloads(MockWebServer server) throws Exception {
        RecordedRequest first = server.takeRequest(3, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getHeader("Authorization")).isNull();
        assertThat(second.getHeader("Authorization")).isNull();
        assertThat(first.getHeader("Content-Type")).contains("application/x-protobuf");
        return Map.of(first.getPath(), first.getBody().readByteArray(), second.getPath(), second.getBody().readByteArray());
    }

    // Minimal protobuf field traversal of the actual OTLP wire; no model serializer mocks.
    private static byte[] message(byte[] payload, int... path) {
        for (int field : path) {
            var input = new ByteArrayInputStream(payload);
            byte[] found = new byte[0];
            while (input.available() > 0) {
                long tag = varint(input);
                int wire = (int) (tag & 7);
                int length = switch (wire) {
                    case 0 -> { varint(input); yield 0; }
                    case 1 -> 8;
                    case 2 -> Math.toIntExact(varint(input));
                    case 5 -> 4;
                    default -> throw new AssertionError("Unexpected protobuf wire type");
                };
                byte[] value = new byte[length];
                if (length > 0 && input.read(value, 0, length) != length) throw new AssertionError("Truncated protobuf field");
                if (wire == 2 && tag >>> 3 == field) { found = value; break; }
            }
            payload = found;
        }
        return payload;
    }

    private static long varint(ByteArrayInputStream input) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int next = input.read();
            if (next < 0) throw new AssertionError("Truncated protobuf");
            result |= (long) (next & 127) << shift;
            if (next < 128) return result;
        }
        throw new AssertionError("Invalid protobuf varint");
    }

    @TestComponent
    @RestController static class FixtureController {
        @PostMapping("/fixture/success")
        String succeed() { return "ok"; }

        @PostMapping("/fixture/users/{id}")
        String fail(@PathVariable String id, @RequestBody String body) {
            throw new IllegalStateException("SELECT password FROM users WHERE email='" + EMAIL + "' " + SECRET + " " + id + " " + body);
        }
    }
}
