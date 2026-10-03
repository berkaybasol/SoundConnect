package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.modules.notification.push.VenuePushPresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueFcmPayloadTest {
    private final Instant now=Instant.parse("2026-09-24T12:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final HttpClient http=mock(HttpClient.class);
    private FcmHttpV1Transport transport;
    @BeforeEach void setup() {
        var properties=new FcmPushProperties(); properties.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("fixture",Date.from(now.plusSeconds(3600)))),
                new DeadlineHttpTransport(http,properties.getReadTimeout()),json,executor,properties,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup() { executor.shutdownNow(); }
    private Map<String,String> data(String type,String variant) {
        return new HashMap<>(Map.of("presentationVersion","ANDROID_VENUE_V1","notificationId",UUID.randomUUID().toString(),
                "recipientId",UUID.randomUUID().toString(),"type",type,"displayVariant",variant,
                "sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));
    }
    private PushEnvelope envelope(Map<String,String> data) {
        return new PushEnvelope("fixture-token","Private name should not appear","Private request should not appear",data,"fixture",now.plusSeconds(300));
    }
    @Test void allThirteenAllowedCombinationsAreDataOnlyAndPreserveExactContract() {
        int count=0;
        for(var type:VenuePushPresentation.TYPES) for(var variant:VenuePushPresentation.Variant.values()) {
            if(!VenuePushPresentation.valid(type.name(),variant.name())) continue;
            var data=data(type.name(),variant.name());
            var result=json.valueToTree(transport.payload(envelope(data)));
            assertThat(result.at("/message/data")).isEqualTo(json.valueToTree(data));
            assertThat(result.at("/message/notification").isMissingNode()).isTrue();
            assertThat(result.at("/message/android/notification").isMissingNode()).isTrue();
            assertThat(result.at("/message/apns").isMissingNode()).isTrue();
            assertThat(result.at("/message/android/priority").asText()).isEqualTo("HIGH");
            assertThat(result.at("/message/android/ttl").asText()).isEqualTo("300s");
            assertThat(result.toString()).doesNotContain("Private", "requestId", "venueId", "actorAvatarUrl");
            count++;
        }
        assertThat(count).isEqualTo(13);
    }
    @Test void venueCanNeverUseGenericFallbackOrSmuggleSourceFields() {
        var variants=new ArrayList<Map<String,String>>();
        var legacy=data("ARTIST_VENUE_LINK_APPLICATION_REQUEST","DEFAULT"); legacy.remove("presentationVersion"); variants.add(legacy);
        var old=data("ARTIST_VENUE_LINK_APPLICATION_REQUEST","DEFAULT"); old.put("presentationVersion","ANDROID_DM_V1"); variants.add(old);
        var extra=data("ARTIST_VENUE_LINK_APPLICATION_REQUEST","DEFAULT"); extra.put("requestId",UUID.randomUUID().toString()); variants.add(extra);
        variants.add(data("ARTIST_VENUE_LINK_APPLICATION_REQUEST","PLAN_CONSENT"));
        variants.add(data("EVENT_PERFORMER_APPROVED","PLAN_WITHDRAWN"));
        variants.add(data("VENUE_APPLICATION_REJECTED","DEFAULT"));
        for(var invalid:variants) {
            assertThatThrownBy(()->transport.payload(envelope(invalid))).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.send(envelope(invalid)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);
        }
        verifyNoInteractions(http);
    }
}
