package com.berkayb.soundconnect.modules.notification.push.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.berkayb.soundconnect.modules.notification.push.DmPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.VenuePushPresentation;
import com.berkayb.soundconnect.modules.notification.push.VenueApplicationPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.StudioPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.FollowPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.MediaPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.BandPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.TablePushPresentation;
import com.berkayb.soundconnect.modules.notification.push.CollabPushPresentation;
import com.berkayb.soundconnect.modules.notification.push.OverthinkingPushPresentation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult.Outcome.*;

/** Official HTTP v1, one FCM submission per call; durable job scheduling owns all retries. */
public final class FcmHttpV1Transport implements PushTransport {
    public static final String ANDROID_CHANNEL = "soundconnect_notifications";
    // Generic wake-up alerts share one offline collapse group. Notification payloads are already
    // collapsible in FCM; their explicit collapse_key may be ignored by the provider.
    public static final String ANDROID_COLLAPSE_KEY = "soundconnect_updates";
    private static final Set<String> KNOWN_CODES = Set.of("UNREGISTERED", "SENDER_ID_MISMATCH",
            "INVALID_ARGUMENT", "QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL", "THIRD_PARTY_AUTH_ERROR",
            "APNS_AUTH_ERROR", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
            "RESOURCE_EXHAUSTED", "DEADLINE_EXCEEDED");
    private final GoogleCredentials credentials;
    private final DeadlineHttpTransport http;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final Duration totalTimeout;
    private final URI endpoint;
    private final Clock clock;

    FcmHttpV1Transport(GoogleCredentials credentials, DeadlineHttpTransport http, ObjectMapper mapper,
                       ExecutorService executor, FcmPushProperties properties, Clock clock) {
        this.credentials = credentials;
        this.http = http;
        this.mapper = mapper;
        this.executor = executor;
        this.totalTimeout = properties.getTotalTimeout();
        this.endpoint = URI.create("https://fcm.googleapis.com/v1/projects/"
                + properties.getProjectId() + "/messages:send");
        this.clock = clock;
    }

    @Override
    public PushSendResult send(PushEnvelope envelope) {
        Future<PushSendResult> task;
        try {
            task = executor.submit(() -> {
                http.begin(totalTimeout);
                try {
                    return sendOnce(envelope);
                } finally {
                    http.end();
                }
            });
        } catch (RejectedExecutionException busy) {
            return failure(RETRYABLE_FAILURE, "LOCAL_CAPACITY", Duration.ofSeconds(1));
        }
        try {
            return task.get(totalTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            return failure(RETRYABLE_FAILURE, "SEND_TIMEOUT", null);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failure(RETRYABLE_FAILURE, "SEND_INTERRUPTED", null);
        } catch (ExecutionException unexpected) {
            // Exception messages may contain token, request, response or credential contents.
            return failure(RETRYABLE_FAILURE, "TRANSPORT_FAILURE", null);
        }
    }

    private PushSendResult sendOnce(PushEnvelope envelope) {
        if (!valid(envelope)) {
            return failure(PERMANENT_FAILURE, "INVALID_ENVELOPE", null);
        }
        if (!envelope.expiresAt().isAfter(clock.instant())) {
            return failure(PERMANENT_FAILURE, "EXPIRED", null);
        }
        try {
            byte[] payload = mapper.writeValueAsBytes(payload(envelope));
            if (payload.length > 4096) {
                return failure(PERMANENT_FAILURE, "PAYLOAD_TOO_LARGE", null);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            try {
                credentials.getRequestMetadata(endpoint).forEach((name, values) ->
                        values.forEach(value -> request.header(name, value)));
            } catch (IOException | RuntimeException unavailable) {
                return failure(RETRYABLE_FAILURE, "AUTH_UNAVAILABLE", Duration.ofSeconds(30));
            }
            // Authentication may have consumed the remaining TTL/deadline.
            http.remainingTimeout();
            if (!envelope.expiresAt().isAfter(clock.instant())) {
                return failure(PERMANENT_FAILURE, "EXPIRED", null);
            }
            // Recompute platform TTL after potentially slow credential refresh.
            request.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload(envelope))));
            return classify(http.send(request));
        } catch (HttpTimeoutException timeout) {
            return failure(RETRYABLE_FAILURE, "SEND_TIMEOUT", null);
        } catch (IOException unavailable) {
            return failure(RETRYABLE_FAILURE, "NETWORK_ERROR", null);
        } catch (IllegalArgumentException invalid) {
            return failure(PERMANENT_FAILURE, "INVALID_ENVELOPE", null);
        }
    }

    private boolean valid(PushEnvelope envelope) {
        return envelope != null && envelope.token() != null && !envelope.token().isBlank()
                && validVenue(envelope)
                && envelope.token().length() <= 4096
                && envelope.title() != null && !envelope.title().isBlank()
                && envelope.body() != null && !envelope.body().isBlank()
                && envelope.expiresAt() != null
                && envelope.collapseKey() != null && !envelope.collapseKey().isBlank()
                && envelope.collapseKey().getBytes(StandardCharsets.UTF_8).length <= 64
                && envelope.data().keySet().stream().noneMatch(key -> key.isBlank()
                || key.equals("from") || key.equals("message_type")
                || key.startsWith("google.") || key.startsWith("gcm."));
    }

    Map<String, Object> payload(PushEnvelope envelope) {
        if (!validVenue(envelope)) throw new IllegalArgumentException("Invalid venue push contract");
        Instant now = clock.instant();
        long ttl = Math.min(Duration.ofDays(28).toSeconds(),
                Math.max(0, Duration.between(now, envelope.expiresAt()).toSeconds()));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", envelope.token());
        if (DmPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                && "DM_NEW_MESSAGE".equals(envelope.data().get("type"))
                || VenuePushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || VenueApplicationPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || StudioPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || FollowPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || MediaPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || BandPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || TablePushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || CollabPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))
                || OverthinkingPushPresentation.VERSION.equals(envelope.data().get("presentationVersion"))) {
            // Only Android installations advertising the native renderer get
            // data-only FCM. The native service builds MessagingStyle, including
            // recipient fencing, avatar fallback and an authenticated tap.
            message.put("data", envelope.data());
            message.put("android", Map.of("priority", "HIGH", "ttl", ttl + "s",
                    "collapse_key", ANDROID_COLLAPSE_KEY));
            return Map.of("message", message);
        }
        message.put("notification", Map.of("title", envelope.title(), "body", envelope.body()));
        message.put("data", envelope.data());
        message.put("android", Map.of("priority", "HIGH", "ttl", ttl + "s",
                "collapse_key", ANDROID_COLLAPSE_KEY, "notification", Map.of(
                        "channel_id", ANDROID_CHANNEL, "tag", envelope.collapseKey(), "sound", "default")));
        message.put("apns", Map.of("headers", Map.of("apns-push-type", "alert",
                        "apns-priority", "10", "apns-expiration", Long.toString(now.plusSeconds(ttl).getEpochSecond()),
                        "apns-collapse-id", envelope.collapseKey()),
                "payload", Map.of("aps", Map.of("sound", "default"))));
        return Map.of("message", message);
    }

    private PushSendResult classify(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        JsonNode json = null;
        try {
            json = mapper.readTree(response.body());
        } catch (IOException ignored) {
            // Do not persist arbitrary provider bodies, which can echo private inputs.
        }
        if (status >= 200 && status < 300) {
            String id = json == null ? "" : json.path("name").asText("");
            if (id.matches("projects/[a-z0-9-]+/messages/[A-Za-z0-9%:._~+\\-]+") && id.length() <= 512) {
                return PushSendResult.accepted(id);
            }
            return failure(RETRYABLE_FAILURE, "INVALID_PROVIDER_RESPONSE", null);
        }
        String code = "HTTP_" + status;
        if (json != null) {
            String supplied = json.path("error").path("status").asText();
            if (KNOWN_CODES.contains(supplied)) {
                code = supplied;
            }
            for (JsonNode detail : json.path("error").path("details")) {
                if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asText())) {
                    supplied = detail.path("errorCode").asText();
                    if (KNOWN_CODES.contains(supplied)) {
                        code = supplied;
                    }
                }
            }
        }
        // INVALID_ARGUMENT may refer to content; only a definitive UNREGISTERED expires a device.
        if (code.equals("UNREGISTERED")) {
            return failure(INVALID_DEVICE, code, null);
        }
        Duration retryAfter = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
        if (status == 429 || status == 408 || status >= 500 || code.equals("QUOTA_EXCEEDED")
                || code.equals("UNAVAILABLE") || code.equals("INTERNAL") || code.equals("DEADLINE_EXCEEDED")) {
            if ((status == 429 || code.equals("QUOTA_EXCEEDED"))
                    && (retryAfter == null || retryAfter.compareTo(Duration.ofMinutes(1)) < 0)) {
                retryAfter = Duration.ofMinutes(1);
            }
            return failure(RETRYABLE_FAILURE, code, retryAfter);
        }
        return failure(PERMANENT_FAILURE, code, null);
    }

    private Duration retryAfter(String value) {
        if (value == null || value.length() > 100) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(Math.min(seconds, Duration.ofDays(1).toSeconds()));
        } catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(clock.instant(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return delay.isNegative() ? Duration.ZERO : delay.compareTo(Duration.ofDays(1)) > 0
                        ? Duration.ofDays(1) : delay;
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static PushSendResult failure(PushSendResult.Outcome outcome, String code, Duration retryAfter) {
        return PushSendResult.failed(outcome, code, retryAfter);
    }
    private static boolean validStudioIdentityAndTime(PushEnvelope envelope) {
        try {
            for(String key:Set.of("notificationId","recipientId")) {
                String value=envelope.data().get(key);
                if(!java.util.UUID.fromString(value).toString().equalsIgnoreCase(value)) return false;
            }
            long sent=Long.parseLong(envelope.data().get("sentAt"));
            long expires=Long.parseLong(envelope.data().get("expiresAt"));
            return sent>0 && expires>sent && envelope.expiresAt()!=null && expires==envelope.expiresAt().toEpochMilli();
        } catch(IllegalArgumentException | NullPointerException invalid) { return false; }
    }
    private static boolean validVenue(PushEnvelope envelope) {
        String type=envelope.data().get("type"), version=envelope.data().get("presentationVersion");
        if((type!=null && type.startsWith("OVERTHINKING_")) || (version!=null && version.startsWith("ANDROID_OVERTHINKING_"))) {
            if(!OverthinkingPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type))
                    || !OverthinkingPushPresentation.VERSION.equals(version)
                    || !envelope.data().keySet().equals(Set.of("notificationId","recipientId","type","presentationVersion","sentAt","expiresAt"))
                    || !validStudioIdentityAndTime(envelope)) return false;
            long sent=Long.parseLong(envelope.data().get("sentAt")), expiry=Long.parseLong(envelope.data().get("expiresAt"));
            return envelope.data().get("sentAt").equals(Long.toString(sent))
                && envelope.data().get("expiresAt").equals(Long.toString(expiry))
                && expiry-sent<=Duration.ofDays(28).toMillis();
        }
        boolean collabType=CollabPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if((type!=null && type.startsWith("COLLAB_")) || (version!=null && version.startsWith("ANDROID_COLLAB_"))) {
            if (!collabType || !CollabPushPresentation.VERSION.equals(version)
                    || !CollabPushPresentation.valid(type,envelope.data().get("displayVariant"))
                    || !envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    || !validStudioIdentityAndTime(envelope)) return false;
            long sent=Long.parseLong(envelope.data().get("sentAt"));
            long expiry=Long.parseLong(envelope.data().get("expiresAt"));
            return envelope.data().get("sentAt").equals(Long.toString(sent))
                    && envelope.data().get("expiresAt").equals(Long.toString(expiry))
                    && expiry-sent<=Duration.ofDays(28).toMillis();
        }
        boolean tableType=TablePushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if((type!=null && type.startsWith("TABLE_")) || (version!=null && version.startsWith("ANDROID_TABLE_"))) {
            if (!tableType || !TablePushPresentation.VERSION.equals(version)
                    || !TablePushPresentation.valid(type,envelope.data().get("displayVariant"))
                    || !envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    || !validStudioIdentityAndTime(envelope)) return false;
            long sent=Long.parseLong(envelope.data().get("sentAt"));
            long expiry=Long.parseLong(envelope.data().get("expiresAt"));
            return envelope.data().get("sentAt").equals(Long.toString(sent))
                    && envelope.data().get("expiresAt").equals(Long.toString(expiry))
                    && expiry-sent<=Duration.ofDays(28).toMillis();
        }
        boolean bandType=BandPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if(bandType || (version!=null && version.startsWith("ANDROID_BAND_")))
            return bandType && BandPushPresentation.VERSION.equals(version)
                    && BandPushPresentation.valid(type,envelope.data().get("displayVariant"))
                    && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    && validStudioIdentityAndTime(envelope)
                    && Long.parseLong(envelope.data().get("expiresAt"))-Long.parseLong(envelope.data().get("sentAt"))<=Duration.ofDays(28).toMillis();
        boolean mediaType=MediaPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if(mediaType || (version!=null && version.startsWith("ANDROID_MEDIA_")))
            return mediaType && MediaPushPresentation.VERSION.equals(version)
                    && MediaPushPresentation.valid(type,envelope.data().get("displayVariant"))
                    && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    && validStudioIdentityAndTime(envelope)
                    && Long.parseLong(envelope.data().get("expiresAt"))-Long.parseLong(envelope.data().get("sentAt"))<=Duration.ofDays(28).toMillis();
        boolean followType=FollowPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if(followType || (version!=null && version.startsWith("ANDROID_FOLLOW_")))
            return followType && FollowPushPresentation.VERSION.equals(version)
                    && FollowPushPresentation.valid(type,envelope.data().get("displayVariant"))
                    && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    && validStudioIdentityAndTime(envelope)
                    && Long.parseLong(envelope.data().get("expiresAt"))-Long.parseLong(envelope.data().get("sentAt"))<=Duration.ofDays(28).toMillis();
        boolean studioType=StudioPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if(studioType || StudioPushPresentation.VERSION.equals(version))
            return studioType && StudioPushPresentation.VERSION.equals(version)
                    && StudioPushPresentation.valid(type,envelope.data().get("displayVariant"))
                    && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"))
                    && validStudioIdentityAndTime(envelope);
        boolean applicationType=VenueApplicationPushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if(applicationType || VenueApplicationPushPresentation.VERSION.equals(version))
            return applicationType && VenueApplicationPushPresentation.VERSION.equals(version)
                    && "DEFAULT".equals(envelope.data().get("displayVariant"))
                    && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                        "presentationVersion","displayVariant","sentAt","expiresAt"));
        boolean venueType=VenuePushPresentation.TYPES.stream().anyMatch(value->value.name().equals(type));
        if (!venueType && !VenuePushPresentation.VERSION.equals(version)) return true;
        return venueType && VenuePushPresentation.VERSION.equals(version)
                && VenuePushPresentation.valid(type,envelope.data().get("displayVariant"))
                && envelope.data().keySet().equals(Set.of("notificationId","recipientId","type",
                    "presentationVersion","displayVariant","sentAt","expiresAt"));
    }

}
