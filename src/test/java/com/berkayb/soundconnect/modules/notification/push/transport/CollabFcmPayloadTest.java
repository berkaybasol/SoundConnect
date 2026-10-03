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

class CollabFcmPayloadTest {
    final Instant now=Instant.parse("2026-10-01T12:00:00Z");
    final ObjectMapper json=new ObjectMapper();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final HttpClient http=mock(HttpClient.class);
    FcmHttpV1Transport transport;
    @BeforeEach void setup(){
        var p=new FcmPushProperties();p.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("isolated",Date.from(now.plusSeconds(3600)))),
            new DeadlineHttpTransport(http,p.getReadTimeout()),json,executor,p,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup(){executor.shutdownNow();}
    Map<String,String> data(String type,String variant){return new HashMap<>(Map.of("presentationVersion",CollabPushPresentation.VERSION,
        "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
        "displayVariant",variant,"sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private name","Private phone title review note",data,"fixture",now.plusSeconds(300));}
    @Test void everyTypeAndBothDecisionsHaveOnlySevenDataFields(){
        for(var type:CollabPushPresentation.TYPES) for(String variant:type==NotificationType.COLLAB_REPORT_RESOLVED?List.of("REMOVE_LISTING","DISMISS"):List.of("DEFAULT")) {
            var d=data(type.name(),variant);var wire=json.valueToTree(transport.payload(envelope(d)));
            assertThat(wire.at("/message/data")).isEqualTo(json.valueToTree(d));
            for(String key:List.of("/message/notification","/message/android/notification","/message/apns"))assertThat(wire.at(key).isMissingNode()).isTrue();
            assertThat(wire.toString()).doesNotContain("Private");
        }
        verifyNoInteractions(http);
    }
    @Test void missingExtraMixedFamilyMalformedIdAndUnknownVariantFailBeforeTransport(){
        var original=data("COLLAB_APPLICATION_RECEIVED","DEFAULT");var bad=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);bad.add(d);}
        for(String key:List.of("listingId","applicationId","jobId","reviewId","reportId","actorId","title","message","phone","note","senderName","avatarUrl","route","deepLink")){var d=new HashMap<>(original);d.put(key,"Private");bad.add(d);}
        for(var pair:Map.of("presentationVersion","ANDROID_TABLE_V1","type","TABLE_EXPIRED","displayVariant","REMOVE_LISTING","recipientId","1-1-1-1-1","notificationId","bad","sentAt","+"+now.toEpochMilli(),"expiresAt","0").entrySet()){var d=new HashMap<>(original);d.put(pair.getKey(),pair.getValue());bad.add(d);}
        bad.add(data("COLLAB_UNKNOWN","DEFAULT"));bad.add(data("COLLAB_REPORT_RESOLVED","DEFAULT"));
        for(var d:bad){assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);}
        verifyNoInteractions(http);
    }
    @Test void expiryMustMatchEnvelopeAndCanonicalWireLifetime(){
        var d=data("COLLAB_LISTING_EXPIRED","DEFAULT");
        for(String value:List.of("-1","0","9223372036854775808","0"+now.toEpochMilli()," "+now.toEpochMilli())) {
            var bad=new HashMap<>(d);bad.put("sentAt",value);assertThatThrownBy(()->transport.payload(envelope(bad))).isInstanceOf(IllegalArgumentException.class);
        }
        var wrong=new PushEnvelope("fixture","title","body",d,"fixture",now.plusSeconds(301));
        assertThatThrownBy(()->transport.payload(wrong)).isInstanceOf(IllegalArgumentException.class);
        d.put("sentAt",Long.toString(now.minus(Duration.ofDays(28)).toEpochMilli()));
        assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void v9RetainsEveryOlderFamilyAndV8NeverAcceptsCollab(){
        String v9=CollabPushPresentation.CAPABILITY;
        assertThat(VenuePushPresentation.supportsDm(v9)).isTrue();assertThat(VenuePushPresentation.supportsVenue(v9)).isTrue();
        assertThat(VenueApplicationPushPresentation.supportsApplication(v9)).isTrue();assertThat(StudioPushPresentation.supportsStudio(v9)).isTrue();
        assertThat(FollowPushPresentation.supportsFollow(v9)).isTrue();assertThat(MediaPushPresentation.supportsMedia(v9)).isTrue();
        assertThat(BandPushPresentation.supportsBand(v9)).isTrue();assertThat(TablePushPresentation.supportsTable(v9)).isTrue();
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5","ANDROID_NATIVE_V6","ANDROID_NATIVE_V7","ANDROID_NATIVE_V8","ANDROID_NATIVE_V11",""))assertThat(CollabPushPresentation.supportsCollab(v)).isFalse();
        assertThat(CollabPushPresentation.supportsCollab(null)).isFalse();
    }
}
