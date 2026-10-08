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

class FollowFcmPayloadTest {
    private final Instant now=Instant.parse("2026-09-24T12:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final HttpClient http=mock(HttpClient.class);
    private FcmHttpV1Transport transport;
    @BeforeEach void setup(){
        var properties=new FcmPushProperties();properties.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("fixture",Date.from(Instant.now().plusSeconds(3600)))),
                new DeadlineHttpTransport(http,properties.getReadTimeout()),json,executor,properties,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void cleanup(){executor.shutdownNow();}
    private Map<String,String> data(String type,String variant){return new HashMap<>(Map.of("presentationVersion",FollowPushPresentation.VERSION,
            "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
            "displayVariant",variant,"sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    private PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private title","Private phone reservation",data,"fixture",now.plusSeconds(300));}
    @Test void bothTypesSerializeSevenDataFieldsWithoutIdentityOrOsFallback() throws Exception {
        for(var type:FollowPushPresentation.TYPES){
            var d=data(type.name(),"DEFAULT");
            var result=FcmWireAssertions.sendNative(transport,http,envelope(d),"300s");
            assertThat(result.at("/message/data")).isEqualTo(json.valueToTree(d));
            for(String key:List.of("/message/notification","/message/android/notification","/message/apns")) assertThat(result.at(key).isMissingNode()).isTrue();
            assertThat(result.toString()).doesNotContain("Private");
        }
    }
    @Test void malformedMixedUnknownAndSnapshotFieldsNeverFallbackOrReachHttp(){
        var original=data("SOCIAL_NEW_FOLLOWER","DEFAULT");
        var invalids=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);invalids.add(d);}
        for(String key:List.of("body","title","followerId","bandId","username","senderName","senderAvatarUrl","route","url")){var d=new HashMap<>(original);d.put(key,"Private");invalids.add(d);}
        for(var mutation:Map.of("presentationVersion","ANDROID_FOLLOW_V2","type","SOCIAL_LIKE","displayVariant","STUDIO_APPROVED","notificationId","1-1-1-1-1","recipientId","bad","sentAt","-1","expiresAt","0").entrySet()){
            var d=new HashMap<>(original);d.put(mutation.getKey(),mutation.getValue());invalids.add(d);
        }
        for(var d:invalids){assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);}
        verifyNoInteractions(http);
    }
    @Test void v5PreservesEveryPriorPresentationAndLegacyDoesNotAcquireFollow(){
        assertThat(VenuePushPresentation.supportsDm(FollowPushPresentation.CAPABILITY)).isTrue();
        assertThat(VenuePushPresentation.supportsVenue(FollowPushPresentation.CAPABILITY)).isTrue();
        assertThat(VenueApplicationPushPresentation.supportsApplication(FollowPushPresentation.CAPABILITY)).isTrue();
        assertThat(StudioPushPresentation.supportsStudio(FollowPushPresentation.CAPABILITY)).isTrue();
        assertThat(FollowPushPresentation.supportsFollow(null)).isFalse();
        for(String v:List.of("ANDROID_NATIVE_V5","ANDROID_NATIVE_V6","ANDROID_NATIVE_V7","ANDROID_NATIVE_V8","ANDROID_NATIVE_V9","ANDROID_NATIVE_V10")) assertThat(FollowPushPresentation.supportsFollow(v)).isTrue();
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V12","")) assertThat(FollowPushPresentation.supportsFollow(v)).isFalse();
    }
}
