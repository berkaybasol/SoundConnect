package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.modules.notification.push.*;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
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

class StudioFcmPayloadTest {
    private final Instant now=Instant.parse("2026-09-24T12:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final HttpClient http=mock(HttpClient.class);
    private FcmHttpV1Transport transport;
    @BeforeEach void setup(){
        var properties=new FcmPushProperties();properties.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("fixture",Date.from(now.plusSeconds(3600)))),
                new DeadlineHttpTransport(http,properties.getReadTimeout()),json,executor,properties,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup(){executor.shutdownNow();}
    private Map<String,String> data(String type,String variant){return new HashMap<>(Map.of("presentationVersion",StudioPushPresentation.VERSION,
            "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
            "displayVariant",variant,"sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    private PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private title","Private phone reservation",data,"fixture",now.plusSeconds(300));}
    @Test void exactlyNineValidTypeVariantPairsAreDataOnlyWithoutPrivateFallback(){
        int supported=0;
        for(var type:StudioPushPresentation.TYPES) for(var variant:StudioPushPresentation.Variant.values()){
            var d=data(type.name(),variant.name());
            if(!StudioPushPresentation.valid(type.name(),variant.name())){
                assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);continue;
            }
            supported++;var result=json.valueToTree(transport.payload(envelope(d)));
            assertThat(result.at("/message/data")).isEqualTo(json.valueToTree(d));
            for(String key:List.of("/message/notification","/message/android/notification","/message/apns"))assertThat(result.at(key).isMissingNode()).isTrue();
            assertThat(result.toString()).doesNotContain("Private");
        }
        assertThat(supported).isEqualTo(9);verifyNoInteractions(http);
    }
    @Test void missingExtraIdentityTimestampVersionAndTypeMutationsFailBeforeHttp(){
        var original=data("STUDIO_RESERVATION_CREATED","STUDIO_CREATED_PENDING");
        var invalids=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);invalids.add(d);}
        for(String key:List.of("body","roomId","reservationId","phone","senderAvatarUrl")){var d=new HashMap<>(original);d.put(key,"Private");invalids.add(d);}
        for(var mutation:Map.of("presentationVersion","ANDROID_VENUE_V1","type","DM_NEW_MESSAGE","displayVariant","DEFAULT",
                "notificationId","bad-id","recipientId","bad-id","sentAt","-1","expiresAt","0").entrySet()){
            var d=new HashMap<>(original);d.put(mutation.getKey(),mutation.getValue());invalids.add(d);
        }
        var wrongExpiry=new HashMap<>(original);wrongExpiry.put("expiresAt",Long.toString(now.plusSeconds(301).toEpochMilli()));invalids.add(wrongExpiry);
        for(var d:invalids){
            assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);
        }
        verifyNoInteractions(http);
    }
    @Test void v4RetainsDmVenueAndApplicationSupportWithoutUpgradingOlderStudioCapabilities(){
        for(String capability:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4")){
            assertThat(VenuePushPresentation.supportsDm(capability)).isTrue();
            assertThat(VenuePushPresentation.supportsVenue(capability)).isEqualTo(!capability.equals("ANDROID_DM_V1"));
            assertThat(VenueApplicationPushPresentation.supportsApplication(capability)).isEqualTo(capability.equals("ANDROID_NATIVE_V3")||capability.equals("ANDROID_NATIVE_V4"));
            assertThat(StudioPushPresentation.supportsStudio(capability)).isEqualTo(capability.equals("ANDROID_NATIVE_V4"));
        }
        assertThat(StudioPushPresentation.valid(NotificationType.DM_NEW_MESSAGE.name(),"STUDIO_APPROVED")).isFalse();
    }
}