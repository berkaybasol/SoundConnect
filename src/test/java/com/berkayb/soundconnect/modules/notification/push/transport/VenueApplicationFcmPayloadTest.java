package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.modules.notification.push.VenueApplicationPushPresentation;
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

class VenueApplicationFcmPayloadTest {
    private final Instant now=Instant.parse("2026-09-24T12:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final HttpClient http=mock(HttpClient.class);
    private FcmHttpV1Transport transport;
    @BeforeEach void setup() {
        var properties=new FcmPushProperties();properties.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("fixture",Date.from(Instant.now().plusSeconds(3600)))),
                new DeadlineHttpTransport(http,properties.getReadTimeout()),json,executor,properties,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup(){executor.shutdownNow();}
    private Map<String,String> data(String type) {return new HashMap<>(Map.of("presentationVersion",VenueApplicationPushPresentation.VERSION,
            "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
            "displayVariant","DEFAULT","sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    private PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private name","Private rejection reason",data,"fixture",now.plusSeconds(300));}
    @Test void bothDecisionTypesAreStrictDataOnlyWithoutPrivateFallback() throws Exception {
        for(var type:VenueApplicationPushPresentation.TYPES) {
            var data=data(type.name());var result=FcmWireAssertions.sendNative(transport,http,envelope(data),"300s");
            assertThat(result.at("/message/data")).isEqualTo(json.valueToTree(data));
            assertThat(result.at("/message/notification").isMissingNode()).isTrue();
            assertThat(result.at("/message/android/notification").isMissingNode()).isTrue();
            assertThat(result.at("/message/apns").isMissingNode()).isTrue();assertThat(result.toString()).doesNotContain("Private");
        }
    }
    @Test void wrongVersionTypeVariantOrExtraSourceFieldFailsBeforeAnyHttp() {
        for(String mutation:List.of("missing","legacy","venueV1","wrongtype","variant","privateextra")) {
            var data=data("VENUE_APPLICATION_REJECTED");
            switch(mutation){case "missing"->data.remove("presentationVersion");case "legacy"->data.put("presentationVersion","ANDROID_DM_V1");
                case "venueV1"->data.put("presentationVersion","ANDROID_VENUE_V1");case "wrongtype"->data.put("type","DM_NEW_MESSAGE");
                case "variant"->data.put("displayVariant","PLAN_CONSENT");case "privateextra"->data.put("reason","private");}
            assertThatThrownBy(()->transport.payload(envelope(data))).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.send(envelope(data)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);
        }
        verifyNoInteractions(http);
    }
}
