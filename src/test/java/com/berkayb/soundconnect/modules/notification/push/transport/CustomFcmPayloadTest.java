package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.modules.notification.push.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.*;
import org.junit.jupiter.api.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomFcmPayloadTest {
    Instant now=Instant.parse("2026-10-07T12:00:00Z");ObjectMapper json=new ObjectMapper();
    ExecutorService executor=Executors.newSingleThreadExecutor();HttpClient http=mock(HttpClient.class);FcmHttpV1Transport transport;
    @BeforeEach void setup(){var p=new FcmPushProperties();p.setProjectId("soundconnect-test");
        transport=new FcmHttpV1Transport(GoogleCredentials.create(new AccessToken("fixture",Date.from(now.plusSeconds(99999999)))),
            new DeadlineHttpTransport(http,p.getReadTimeout()),json,executor,p,Clock.fixed(now,ZoneOffset.UTC));}
    @AfterEach void cleanup(){executor.shutdownNow();}
    Map<String,String> data(){return new HashMap<>(Map.of("type","ADMIN_BROADCAST","presentationVersion",CustomPushPresentation.VERSION,
        "notificationId",UUID.randomUUID().toString(),"recipientId",UUID.randomUUID().toString(),"title","Yeni etkinlikler","body","Bu haftanın etkinliklerini keşfet.",
        "sentAt",Long.toString(now.toEpochMilli()),"expiresAt",Long.toString(now.plusSeconds(300).toEpochMilli())));}
    PushEnvelope envelope(Map<String,String> d){return new PushEnvelope("fixture","title","body",d,"fixture",now.plusSeconds(300));}
    @Test void customWireUsesEightFieldsNoCollapseAndNoTargetMetadata()throws Exception{
        var d=data();var wire=FcmWireAssertions.sendNative(transport,http,envelope(d),"300s");
        assertThat(wire.at("/message/data")).isEqualTo(json.valueToTree(d));
        assertThat(wire.at("/message/notification").isMissingNode()).isTrue();
        assertThat(wire.at("/message/android/collapse_key").isMissingNode()).isTrue();
    }
    @Test void rejectsMissingExtraInvalidTextAndForgedNativeContractBeforeNetwork(){
        var original=data();var bad=new ArrayList<Map<String,String>>();
        for(String key:original.keySet()){var d=new HashMap<>(original);d.remove(key);bad.add(d);}
        for(String key:List.of("targetId","campaignId","url","email")){var d=new HashMap<>(original);d.put(key,"private");bad.add(d);}
        for(String title:List.of(""," ","a\nline","😀".repeat(61),"a\u202Eb","a\u2029b","a\uD800b")){var d=new HashMap<>(original);d.put("title",title);bad.add(d);}
        for(var entry:Map.of("type","DM_NEW_MESSAGE","presentationVersion","ANDROID_CUSTOM_V2","recipientId","1-1-1-1-1","sentAt","+"+now.toEpochMilli()).entrySet()){
            var d=new HashMap<>(original);d.put(entry.getKey(),entry.getValue());bad.add(d);}
        for(var d:bad){assertThatThrownBy(()->transport.payload(envelope(d))).isInstanceOf(IllegalArgumentException.class);assertThat(transport.send(envelope(d)).outcome()).isEqualTo(PushSendResult.Outcome.PERMANENT_FAILURE);}
        verifyNoInteractions(http);
    }
    @Test void v11RetainsAllExistingNativeFamilies(){
        String v=CustomPushPresentation.CAPABILITY;
        assertThat(VenuePushPresentation.supportsDm(v)).isTrue();assertThat(VenuePushPresentation.supportsVenue(v)).isTrue();
        assertThat(VenueApplicationPushPresentation.supportsApplication(v)).isTrue();assertThat(StudioPushPresentation.supportsStudio(v)).isTrue();
        assertThat(FollowPushPresentation.supportsFollow(v)).isTrue();assertThat(MediaPushPresentation.supportsMedia(v)).isTrue();
        assertThat(BandPushPresentation.supportsBand(v)).isTrue();assertThat(TablePushPresentation.supportsTable(v)).isTrue();
        assertThat(CollabPushPresentation.supportsCollab(v)).isTrue();assertThat(OverthinkingPushPresentation.supportsOverthinking(v)).isTrue();
    }
}
