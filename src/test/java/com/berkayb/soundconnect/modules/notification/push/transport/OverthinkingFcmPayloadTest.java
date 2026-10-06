package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.modules.notification.push.*;
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

class OverthinkingFcmPayloadTest {
    final Instant now=Instant.parse("2026-10-01T12:00:00Z");
    final ObjectMapper json=new ObjectMapper();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final HttpClient http=mock(HttpClient.class);
    FcmHttpV1Transport transport;
    @BeforeEach void setup(){
        var p=new FcmPushProperties();p.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("isolated",Date.from(Instant.now().plusSeconds(3600)))),
            new DeadlineHttpTransport(http,p.getReadTimeout()),json,executor,p,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup(){executor.shutdownNow();}
    Map<String,String> data(String type){return new HashMap<>(Map.of("presentationVersion",OverthinkingPushPresentation.VERSION,
        "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
        "sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private name","Private anonymous content",data,"fixture",now.plusSeconds(300));}
    @Test void everyTypeHasOnlySixDataFieldsAndNoIdentityOrPrivateNotification() throws Exception {
        for(var type:OverthinkingPushPresentation.TYPES) {
            var d=data(type.name());var wire=FcmWireAssertions.sendNative(transport,http,envelope(d),"300s");
            assertThat(wire.at("/message/data")).isEqualTo(json.valueToTree(d));
            for(String key:List.of("/message/notification","/message/android/notification","/message/apns"))assertThat(wire.at(key).isMissingNode()).isTrue();
            assertThat(wire.toString()).doesNotContain("Private");
        }
    }
    @Test void invalidContractsFailBeforeTransport(){
        var original=data("OVERTHINKING_REVEAL_REQUEST_RECEIVED");var bad=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);bad.add(d);}
        for(String key:List.of("displayVariant","postId","revealRequestId","authorId","requesterId","postTitle","content","title","message","avatarUrl","canViewAuthor")){var d=new HashMap<>(original);d.put(key,"Private");bad.add(d);}
        for(var pair:Map.of("presentationVersion","ANDROID_COLLAB_V1","type","COLLAB_APPLICATION_RECEIVED","recipientId","1-1-1-1-1","notificationId","bad","sentAt","+"+now.toEpochMilli(),"expiresAt","0").entrySet()){var d=new HashMap<>(original);d.put(pair.getKey(),pair.getValue());bad.add(d);}
        bad.add(data("OVERTHINKING_UNKNOWN"));
        for(var d:bad){assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);}
        verifyNoInteractions(http);
    }
    @Test void envelopeAndCanonicalWireLifetimeMustAgree(){
        var d=data("OVERTHINKING_REVEAL_REQUEST_APPROVED");
        for(String value:List.of("-1","0","9223372036854775808","0"+now.toEpochMilli()," "+now.toEpochMilli())) {
            var bad=new HashMap<>(d);bad.put("sentAt",value);assertThatThrownBy(()->transport.payload(envelope(bad))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->transport.payload(new PushEnvelope("fixture","t","b",d,"fixture",now.plusSeconds(301)))).isInstanceOf(IllegalArgumentException.class);
        d.put("sentAt",Long.toString(now.minus(Duration.ofDays(28)).toEpochMilli()));
        assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void v10RetainsAllPreviousFamiliesAndOlderUnknownCapabilitiesCannotReceiveReveal(){
        String v10=OverthinkingPushPresentation.CAPABILITY;
        assertThat(VenuePushPresentation.supportsDm(v10)).isTrue();assertThat(VenuePushPresentation.supportsVenue(v10)).isTrue();
        assertThat(VenueApplicationPushPresentation.supportsApplication(v10)).isTrue();assertThat(StudioPushPresentation.supportsStudio(v10)).isTrue();
        assertThat(FollowPushPresentation.supportsFollow(v10)).isTrue();assertThat(MediaPushPresentation.supportsMedia(v10)).isTrue();
        assertThat(BandPushPresentation.supportsBand(v10)).isTrue();assertThat(TablePushPresentation.supportsTable(v10)).isTrue();assertThat(CollabPushPresentation.supportsCollab(v10)).isTrue();
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5","ANDROID_NATIVE_V6","ANDROID_NATIVE_V7","ANDROID_NATIVE_V8","ANDROID_NATIVE_V9","ANDROID_NATIVE_V11",""))assertThat(OverthinkingPushPresentation.supportsOverthinking(v)).isFalse();
        assertThat(OverthinkingPushPresentation.supportsOverthinking(null)).isFalse();
    }
}
