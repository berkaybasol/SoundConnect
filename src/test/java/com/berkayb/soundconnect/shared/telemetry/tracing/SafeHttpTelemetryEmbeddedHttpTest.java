package com.berkayb.soundconnect.shared.telemetry.tracing;

import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.servlet.WebMvcObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real loopback HTTP + Boot observation registration + advice, without product scanning or services. */
class SafeHttpTelemetryEmbeddedHttpTest {
    @Test void bootRegistersHandlerAndExportsSafeCorrelatedAdviceFailureOverRealHttp() throws Exception {
        try (var collector = new MockWebServer()) {
            collector.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(200); }
            });
            collector.start();
            try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.SERVLET).run(
                    "--spring.config.location=optional:classpath:/telemetry-isolated-fixture.yml", "--spring.config.import=",
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                    "--app.observability.http-telemetry.enabled=true",
                    "--app.observability.dependency-telemetry.enabled=true",
                    "--app.telemetry.metrics.service-name=soundconnect-api",
                    "--app.observability.http-telemetry.sample-probability=1",
                    "--app.observability.http-telemetry.collector-endpoint=http://127.0.0.1:" + collector.getPort());
                 var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/fixture/account/private-account-74?secret=query-secret-62"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer bearer-secret-14")
                        .header("Content-Type", "text/plain")
                        .header("traceparent", "00-11223344556677889900aabbccddeeff-1122334455667788-01")
                        .POST(HttpRequest.BodyPublishers.ofString("private-body-82")).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(500);
                assertThat(response.body()).doesNotContain("private-exception-53", "SELECT private_sql");
                var telemetry = context.getBean(SafeHttpObservationHandler.class);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    telemetry.flush();
                    assertThat(collector.getRequestCount()).isEqualTo(2);
                });
                Map<String, byte[]> wire = new LinkedHashMap<>();
                for (int i = 0; i < 2; i++) {
                    var exported = collector.takeRequest(1, TimeUnit.SECONDS);
                    assertThat(exported).isNotNull();
                    assertThat(exported.getHeader("Authorization")).isNull();
                    wire.put(exported.getPath(), exported.getBody().readByteArray());
                }
                var traceExport = ExportTraceServiceRequest.parseFrom(wire.get("/v1/traces"));
                var logExport = ExportLogsServiceRequest.parseFrom(wire.get("/v1/logs"));
                assertThat(traceExport.getResourceSpansList()).hasSize(1);
                var scopes = traceExport.getResourceSpans(0).getScopeSpansList();
                assertThat(scopes).hasSize(2);
                var scope = scopes.stream().filter(item -> item.getScope().getName().equals("soundconnect.safe-http")).findFirst().orElseThrow();
                assertThat(scope.getScope().getName()).isEqualTo("soundconnect.safe-http");
                assertThat(scope.getSpansList()).hasSize(1);
                var span = scope.getSpans(0);
                var dependencyScope = scopes.stream().filter(item -> item.getScope().getName().equals("soundconnect.safe-dependency")).findFirst().orElseThrow();
                assertThat(dependencyScope.getSpansList()).hasSize(1);
                var dependency = dependencyScope.getSpans(0);
                assertThat(dependency.getName()).isEqualTo("s3 head_object");
                assertThat(dependency.getParentSpanId()).isEqualTo(span.getSpanId());
                assertThat(dependency.getTraceId()).isEqualTo(span.getTraceId());
                assertThat(dependency.getAttributesList().stream().map(attribute -> attribute.getKey()).toList())
                        .containsExactlyInAnyOrder("dependency.provider", "dependency.operation", "dependency.result");
                assertThat(dependency.getEventsList()).isEmpty();
                assertThat(dependency.getLinksList()).isEmpty();
                assertThat(dependency.getTraceState()).isEmpty();
                assertThat(dependency.getStatus().getMessage()).isEmpty();
                var log = logExport.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
                assertThat(span.getName()).isEqualTo("POST /fixture/account/{id}");
                assertThat(span.getTraceId()).isEqualTo(log.getTraceId());
                assertThat(span.getSpanId()).isEqualTo(log.getSpanId());
                assertThat(span.getParentSpanId().isEmpty()).isTrue();
                assertThat(span.getEventsList()).isEmpty();
                assertThat(span.getLinksList()).isEmpty();
                assertThat(span.getAttributesList().stream().map(attribute -> attribute.getKey()).toList())
                        .containsExactlyInAnyOrder("http.request.method", "http.route", "http.outcome", "exception.type", "code.file.name", "code.line.number");
                assertThat(log.getBody().getStringValue()).isEqualTo("HTTP request failed");
                assertThat(span.getAttributesList()).anySatisfy(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo("code.file.name");
                    assertThat(attribute.getValue().getStringValue()).isEqualTo("SafeHttpTelemetryEmbeddedHttpTest.java");
                });
                String exportedText = traceExport + " " + logExport;
                assertThat(exportedText).doesNotContain("private-account-74", "query-secret-62", "bearer-secret-14",
                        "private-body-82", "private-exception-53", "SELECT private_sql", "baggage", "exception.stacktrace");
                assertThat(java.util.HexFormat.of().formatHex(span.getTraceId().toByteArray()))
                        .isNotEqualTo("11223344556677889900aabbccddeeff");
                assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
                assertThat(context.getBeanNamesForType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)).isEmpty();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            ObservationAutoConfiguration.class, WebMvcObservationAutoConfiguration.class})
    @Import({SafeHttpTelemetryConfiguration.class, GlobalExceptionHandler.class, FixtureController.class})
    static class Fixture {
        @Bean SimpleMeterRegistry registry() { return new SimpleMeterRegistry(); }
    }

    @TestComponent
    @RestController static class FixtureController {
        private final SafeDependencyTelemetry telemetry;
        FixtureController(SafeDependencyTelemetry telemetry) { this.telemetry = telemetry; }
        @PostMapping("/fixture/account/{id}")
        String fail(@PathVariable String id, @RequestBody String body) {
            return telemetry.observe(SafeDependencyTelemetry.Operation.S3_HEAD_OBJECT, () -> {
                throw new IllegalStateException("private-exception-53 SELECT private_sql " + id + " " + body);
            });
        }
    }
}
