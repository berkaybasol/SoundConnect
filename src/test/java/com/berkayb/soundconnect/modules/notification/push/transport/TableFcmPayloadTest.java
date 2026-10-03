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

class TableFcmPayloadTest {
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
    private Map<String,String> data(String type,String variant){return new HashMap<>(Map.of("presentationVersion",TablePushPresentation.VERSION,
            "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"type",type,
            "displayVariant",variant,"sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    private PushEnvelope envelope(Map<String,String> data){return new PushEnvelope("fixture","Private title","Private phone reservation",data,"fixture",now.plusSeconds(300));}
    @Test void sevenTypesSerializeSevenDataFieldsWithoutIdentityOrOsFallback(){
        for(var type:TablePushPresentation.TYPES){
            var d=data(type.name(),type==NotificationType.TABLE_CANCELLED?"OWNER_CANCELLED":"DEFAULT");
            var result=json.valueToTree(transport.payload(envelope(d)));
            assertThat(result.at("/message/data")).isEqualTo(json.valueToTree(d));
            for(String key:List.of("/message/notification","/message/android/notification","/message/apns")) assertThat(result.at(key).isMissingNode()).isTrue();
            assertThat(result.toString()).doesNotContain("Private");
        }
        verifyNoInteractions(http);
    }
    @Test void malformedMixedUnknownAndSnapshotFieldsNeverFallbackOrReachHttp(){
        var original=data("TABLE_JOIN_REQUEST_RECEIVED","DEFAULT");
        var invalids=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);invalids.add(d);}
        for(String key:List.of("actorId","mediaId","commentId","sourceUrl","body","title","followerId","bandId","username","senderName","senderAvatarUrl","route","url")){var d=new HashMap<>(original);d.put(key,"Private");invalids.add(d);}
        for(var mutation:Map.of("presentationVersion","ANDROID_TABLE_V2","type","SOCIAL_NEW_FOLLOWER","displayVariant","STUDIO_APPROVED","notificationId","1-1-1-1-1","recipientId","bad","sentAt","-1","expiresAt","0").entrySet()){
            var d=new HashMap<>(original);d.put(mutation.getKey(),mutation.getValue());invalids.add(d);
        }
        for(var d:invalids){assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);}
        verifyNoInteractions(http);
    }
    @Test void cancellationReasonsAreDistinctAndCanonicalTimesAreRequired(){
        for(String reason:List.of("OWNER_CANCELLED","OWNER_JOINED_ANOTHER_TABLE"))
            assertThat(json.valueToTree(transport.payload(envelope(data("TABLE_CANCELLED",reason)))).at("/message/data/displayVariant").asText()).isEqualTo(reason);
        for(String reason:List.of("DEFAULT","OTHER")) assertThatThrownBy(()->transport.payload(envelope(data("TABLE_CANCELLED",reason)))).isInstanceOf(IllegalArgumentException.class);
        for(String sent:List.of("+"+now.toEpochMilli(),"0"+now.toEpochMilli()," "+now.toEpochMilli(),"9223372036854775808")) {
            var d=data("TABLE_EXPIRED","DEFAULT");d.put("sentAt",sent);
            assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void v8PreservesPrior24AndLegacyDoesNotAcquireTable(){
        assertThat(VenuePushPresentation.supportsDm(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(VenuePushPresentation.supportsVenue(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(VenueApplicationPushPresentation.supportsApplication(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(StudioPushPresentation.supportsStudio(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(FollowPushPresentation.supportsFollow(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(MediaPushPresentation.supportsMedia(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(BandPushPresentation.supportsBand(TablePushPresentation.CAPABILITY)).isTrue();
        assertThat(TablePushPresentation.supportsTable(null)).isFalse();
        for(String v:List.of("ANDROID_NATIVE_V8","ANDROID_NATIVE_V9","ANDROID_NATIVE_V10")) assertThat(TablePushPresentation.supportsTable(v)).isTrue();
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5","ANDROID_NATIVE_V6","ANDROID_NATIVE_V7","ANDROID_NATIVE_V11","")) assertThat(TablePushPresentation.supportsTable(v)).isFalse();
    }
}
