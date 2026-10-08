package com.berkayb.soundconnect.modules.notification.push.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
@EnableConfigurationProperties(FcmPushProperties.class)
public class FcmPushConfiguration {
    private static final String MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @Bean(name = "fcmSendExecutor", destroyMethod = "shutdownNow")
    ExecutorService fcmSendExecutor(FcmPushProperties properties) {
        // No waiting queue: the durable dispatcher retains ownership when every slot is busy.
        return new ThreadPoolExecutor(properties.getMaxConcurrentRequests(), properties.getMaxConcurrentRequests(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("fcm-send-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "fcmHttpClient", destroyMethod = "close")
    HttpClient fcmHttpClient(FcmPushProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_2).build();
    }

    @Bean
    DeadlineHttpTransport fcmDeadlineHttpTransport(@Qualifier("fcmHttpClient") HttpClient client,
                                                  FcmPushProperties properties) {
        return new DeadlineHttpTransport(client, properties.getReadTimeout());
    }

    @Bean(name = "fcmGoogleCredentials")
    GoogleCredentials fcmGoogleCredentials(FcmPushProperties properties, DeadlineHttpTransport http,
                                           @Qualifier("fcmSendExecutor") ExecutorService executor) {
        Future<GoogleCredentials> load = executor.submit(() -> {
            http.begin(properties.getTotalTimeout());
            try {
                GoogleCredentials credentials;
                if (properties.getCredentialsPath() == null || properties.getCredentialsPath().isBlank()) {
                    credentials = GoogleCredentials.getApplicationDefault(() -> http);
                } else {
                    try (InputStream input = Files.newInputStream(Path.of(properties.getCredentialsPath()))) {
                        credentials = ServiceAccountCredentials.fromStream(input, () -> http);
                    }
                }
                return credentials.createScoped(List.of(MESSAGING_SCOPE)).createWithCustomRetryStrategy(false);
            } finally {
                http.end();
            }
        });
        try {
            return load.get(properties.getTotalTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            load.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FCM credential initialization interrupted");
        } catch (Exception unavailable) {
            load.cancel(true);
            // Intentionally do not attach raw provider/JSON exceptions to the startup log.
            throw new IllegalStateException("FCM credentials unavailable; configure ADC or an external service-account file");
        }
    }

    @Bean
    PushTransport fcmPushTransport(@Qualifier("fcmGoogleCredentials") GoogleCredentials credentials,
                                  DeadlineHttpTransport http, ObjectMapper mapper,
                                  @Qualifier("fcmSendExecutor") ExecutorService executor,
                                  FcmPushProperties properties) {
        return new FcmHttpV1Transport(credentials, http, mapper, executor, properties, Clock.systemUTC());
    }
}
