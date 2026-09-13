package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnouncementAnalyticsContractTest {
    private static final UUID CLIENT=UUID.randomUUID(), ANNOUNCEMENT=UUID.randomUUID(), PLAYBACK=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-09-13T12:00:00Z");
    private final ObjectMapper mapper=Jackson2ObjectMapperBuilder.json().build();

    static Stream<AnalyticsRequest.Type> announcementTypes() {
        return Arrays.stream(AnalyticsRequest.Type.values()).filter(AnalyticsRequest.Type::announcement);
    }

    @ParameterizedTest @MethodSource("announcementTypes")
    void strictParserAcceptsBothSourcesWithExactRequiredContext(AnalyticsRequest.Type type) throws Exception {
        for (AnalyticsRequest.Source source : AnalyticsRequest.Source.values()) {
            var row=row(type,source);
            var parsed=mapper.readValue(body(row),AnalyticsRequest.class).observations().getFirst();
            assertThat(parsed.announcementId()).isEqualTo(ANNOUNCEMENT);
            assertThat(parsed.source()).isEqualTo(source);
            assertThat(parsed.playbackId()).isEqualTo(type.video()?PLAYBACK:null);
            assertThat(parsed.impressionToken()).isEqualTo(source==AnalyticsRequest.Source.FEED?"signed-delivery-token":null);
            assertThat(parsed.eventId()).isNull();
        }
    }

    @Test void parserRejectsForgedIdentityWrongContextAndCoercedScalars() throws Exception {
        var valid=row(AnalyticsRequest.Type.ANNOUNCEMENT_IMPRESSION,AnalyticsRequest.Source.FEED);
        for (String field:List.of("announcementId","source","impressionToken")) {
            for (Object value:Arrays.asList(null,13,true,List.of("fake"),Map.of("fake",true),"")) {
                var changed=new LinkedHashMap<>(valid); changed.put(field,value);
                assertThatThrownBy(()->mapper.readValue(body(changed),AnalyticsRequest.class)).as("%s=%s",field,value).isInstanceOf(Exception.class);
            }
        }
        for (String field:List.of("eventId","venueId","sourceEventId","userId","profileType","deliveryId","playbackId")) {
            var changed=new LinkedHashMap<>(valid); changed.put(field,PLAYBACK.toString());
            assertThatThrownBy(()->mapper.readValue(body(changed),AnalyticsRequest.class)).as(field).isInstanceOf(Exception.class);
        }
        var directory=row(AnalyticsRequest.Type.ANNOUNCEMENT_IMPRESSION,AnalyticsRequest.Source.DIRECTORY);
        directory.put("impressionToken","irrelevant-proof");
        assertThatThrownBy(()->mapper.readValue(body(directory),AnalyticsRequest.class)).isInstanceOf(Exception.class);
        var video=row(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_START,AnalyticsRequest.Source.DIRECTORY);
        video.remove("playbackId");
        assertThatThrownBy(()->mapper.readValue(body(video),AnalyticsRequest.class)).isInstanceOf(Exception.class);
    }

    @Test void guestAnnouncementIsRejectedBeforeRateOrStorageAndAuthenticatedContextReachesSameCollector() throws Exception {
        var store=mock(AnalyticsStore.class); var guard=mock(AnalyticsRateGuard.class);
        var clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(NOW);
        var service=new AnalyticsService(store,new AnalyticsIdentity(AnalyticsServiceTest.properties()),guard,clock);
        var request=mapper.readValue(body(row(AnalyticsRequest.Type.ANNOUNCEMENT_IMPRESSION,AnalyticsRequest.Source.DIRECTORY)),AnalyticsRequest.class);
        assertThatThrownBy(()->service.observe(null,request)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(store,guard);
        UUID user=UUID.randomUUID();
        assertThat(service.observe(user,request).acknowledgedIds()).containsExactly(request.observations().getFirst().id());
        verify(store).observe(user,request,NOW);
    }

    @Test void oldReceiptHashRemainsByteCompatibleAndNewSourceAndProofAreImmutablePayload() throws Exception {
        var properties=AnalyticsServiceTest.properties(); var identity=new AnalyticsIdentity(properties);
        var old=new AnalyticsRequest.Observation(UUID.randomUUID(),AnalyticsRequest.Type.EVENT_IMPRESSION,ANNOUNCEMENT,null,null,NOW);
        String payload="payload|"+CLIENT+"|"+old.id()+"|EVENT_IMPRESSION|"+ANNOUNCEMENT+"|null|null|"+NOW;
        var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertThat(identity.payload(CLIENT,old)).containsExactly(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        var first=mapper.readValue(body(row(AnalyticsRequest.Type.ANNOUNCEMENT_IMPRESSION,AnalyticsRequest.Source.FEED)),AnalyticsRequest.class).observations().getFirst();
        var changed=new AnalyticsRequest.Observation(first.id(),first.type(),null,null,null,NOW,ANNOUNCEMENT,AnalyticsRequest.Source.FEED,null,"another-proof");
        assertThat(identity.payload(CLIENT,first)).isNotEqualTo(identity.payload(CLIENT,changed));
    }

    static Map<String,Object> row(AnalyticsRequest.Type type,AnalyticsRequest.Source source) {
        var row=new LinkedHashMap<String,Object>();
        row.put("id",UUID.randomUUID().toString()); row.put("type",type.name()); row.put("observedAt",NOW.toString());
        row.put("announcementId",ANNOUNCEMENT.toString()); row.put("source",source.name());
        if(type.video()) row.put("playbackId",PLAYBACK.toString());
        if(source==AnalyticsRequest.Source.FEED) row.put("impressionToken","signed-delivery-token");
        return row;
    }
    String body(Map<String,Object> row) throws Exception { return mapper.writeValueAsString(Map.of("clientId",CLIENT.toString(),"observations",List.of(row))); }
}
