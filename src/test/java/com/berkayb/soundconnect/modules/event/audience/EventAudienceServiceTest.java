package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.discovery.EventDiscoveryService;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventAudienceServiceTest {
    final UUID user=UUID.randomUUID(), eventId=UUID.randomUUID(), profileId=UUID.randomUUID();
    final Instant now=Instant.parse("2026-09-08T12:00:00Z");
    EventAudienceRepository repository; UserRepository users;
    EventDiscoveryService cards; EventScheduleClock clock; EventAudienceService service; ListenerProfile profile;
    EventResponseDto event; AtomicReference<EventAudienceIntent> saved;
    @BeforeEach void setup() {
        repository=mock(EventAudienceRepository.class); users=mock(UserRepository.class);
        cards=mock(EventDiscoveryService.class); clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(now);
        service=new EventAudienceService(repository,users,cards,clock);
        profile=ListenerProfile.builder().visibilityMode(ListenerVisibilityMode.STANDARD).visibilityChoiceCompleted(true).build(); profile.setId(profileId);
        when(repository.lockActor(user)).thenReturn(Optional.of(user)); when(repository.lockActiveAccountForRead(user)).thenReturn(Optional.of(user));
        when(users.findRoleNamesByUserId(user)).thenReturn(Set.of("ROLE_LISTENER"));
        when(users.findExistingPersonalProfileRoleNames(user)).thenReturn(Set.of("ROLE_LISTENER"));
        when(repository.listenerVisibility(user)).thenAnswer(call -> Optional.of(new EventAudienceRepository.ListenerVisibility() {
            public UUID getProfileId() { return profile.getId(); }
            public String getMode() { return profile.getVisibilityMode().name(); }
            public boolean getChoiceCompleted() { return profile.isVisibilityChoiceCompleted(); }
        }));
        when(repository.listenerUserId(profileId)).thenReturn(Optional.of(user));
        event=event(LocalTime.of(15,0),LocalTime.of(17,0));
        when(cards.present(anyList())).thenReturn(List.of(event));
        saved=new AtomicReference<>(); when(repository.findById(any(EventAudienceIntent.Id.class))).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(repository.saveAndFlush(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });
    }
    EventResponseDto event(LocalTime start, LocalTime end) { return new EventResponseDto(eventId,"Event",null,"Artist",null,null,null,Set.of(),
            UUID.randomUUID(),"Venue","City","District","Neighborhood",LocalDate.of(2026,9,8),start,end,"Description","https://example.test/events/"+eventId); }
    EventIntentUpdate update(EventIntent intent, boolean published, String note, long version) { return new EventIntentUpdate(intent,published,note,version); }

    @Test void initialStateIsPrivateNoneAndReadNeverCreatesAPlan() {
        var state=service.get(user,eventId);
        assertThat(state.intent()).isEqualTo(EventIntent.NONE); assertThat(state.version()).isZero(); assertThat(state.updatedAt()).isNull();
        assertThat(state.publishedOnProfile()).isFalse(); assertThat(state.publicationVisible()).isFalse();
        assertThat(state.canSetIntent()).isTrue(); assertThat(state.canPublish()).isTrue(); assertThat(state.event()).isEqualTo(event);
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void publicationIdentitySurvivesEditsAndGhostButNewPublicationHasANewConversation() {
        var first=service.update(user,eventId,update(EventIntent.GOING,true,"First",0));
        assertThat(first.postId()).isNotNull().isNotEqualTo(eventId);
        var edited=service.update(user,eventId,update(EventIntent.THINKING,true,"Edited",1));
        assertThat(edited.postId()).isEqualTo(first.postId());
        profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
        assertThat(service.get(user,eventId).postId()).isEqualTo(first.postId());
        assertThat(service.get(user,eventId).publicationVisible()).isFalse();
        var unpublished=service.update(user,eventId,update(EventIntent.THINKING,false,null,2));
        assertThat(unpublished.postId()).isNull();
        profile.setVisibilityMode(ListenerVisibilityMode.STANDARD);
        var republished=service.update(user,eventId,update(EventIntent.THINKING,true,"New",3));
        assertThat(republished.postId()).isNotNull().isNotEqualTo(first.postId());
    }

    @Test void deletePostPreservesPrivateIntentAndAllowsRemovalWhenEventUnavailableOrGhost() {
        var published=service.update(user,eventId,update(EventIntent.GOING,true,"A note",0));
        when(repository.publishedEventId(user,published.postId())).thenReturn(Optional.of(eventId));
        profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
        when(cards.present(anyList())).thenReturn(List.of());
        var removed=service.deletePost(user,published.postId());
        assertThat(removed.intent()).isEqualTo(EventIntent.GOING);
        assertThat(removed.publishedOnProfile()).isFalse(); assertThat(removed.postId()).isNull();
        assertThat(removed.note()).isNull(); assertThat(removed.version()).isEqualTo(2);
        assertThat(removed.eventAvailable()).isFalse();
        assertThatThrownBy(() -> service.deletePost(user,published.postId())).isInstanceOf(SoundConnectException.class);
    }
    @Test void lostResponseRetryDoesNotAdvanceVersionAndAbaStaleCommandCannotResurrectEarlierIntent() {
        var first=update(EventIntent.GOING,false,null,0);
        assertThat(service.update(user,eventId,first).version()).isEqualTo(1);
        assertThat(service.update(user,eventId,first).version()).isEqualTo(1);
        service.update(user,eventId,update(EventIntent.THINKING,false,null,1));
        service.update(user,eventId,update(EventIntent.GOING,false,null,2));
        assertThat(catchThrowableOfType(() -> service.update(user,eventId,first),SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.EVENT_INTENT_VERSION_CONFLICT);
        verify(repository,times(3)).saveAndFlush(any());
    }
    @Test void explicitPublicationIsOptionalAndNoneClearsNoteButKeepsVersionTombstone() {
        var privatePlan=service.update(user,eventId,update(EventIntent.THINKING,false,null,0));
        assertThat(privatePlan.publicationVisible()).isFalse();
        var published=service.update(user,eventId,update(EventIntent.THINKING,true,"  Maybe with friends  ",1));
        assertThat(published.note()).isEqualTo("Maybe with friends"); assertThat(published.publicationVisible()).isTrue();
        var going=service.update(user,eventId,update(EventIntent.GOING,true,"Maybe with friends",2));
        assertThat(going.intent()).isEqualTo(EventIntent.GOING); assertThat(saved.get().getPublishedAt()).isEqualTo(now);
        var removed=service.update(user,eventId,update(EventIntent.NONE,false,null,3));
        assertThat(removed.version()).isEqualTo(4); assertThat(removed.note()).isNull(); assertThat(removed.publicationVisible()).isFalse();
        assertThat(saved.get().getPublishedAt()).isNull(); assertThat(saved.get().getIntent()).isEqualTo(EventIntent.NONE);
    }
    @Test void ghostHidesRetainedPublicationRestoresWithStandardAndCanUnpublishWhileGhost() {
        service.update(user,eventId,update(EventIntent.GOING,true,"See you there",0));
        profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
        var hidden=service.get(user,eventId);
        assertThat(hidden.publishedOnProfile()).isTrue(); assertThat(hidden.publicationVisible()).isFalse(); assertThat(hidden.canPublish()).isFalse();
        assertThat(service.posts(user,profileId,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        verify(repository,never()).publicIds(any(),any(),any(),any(),any(),any());
        profile.setVisibilityMode(ListenerVisibilityMode.STANDARD); assertThat(service.get(user,eventId).publicationVisible()).isTrue();
        profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
        assertThat(service.update(user,eventId,update(EventIntent.GOING,false,null,1)).publishedOnProfile()).isFalse();
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.GOING,true,"Again",2))).isInstanceOf(SoundConnectException.class);
    }
    @Test void pendingChoiceCanPlanPrivatelyButCannotPublishOrExposePosts() {
        profile.setVisibilityChoiceCompleted(false);
        assertThat(service.update(user,eventId,update(EventIntent.GOING,false,null,0)).canPublish()).isFalse();
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.GOING,true,null,1))).isInstanceOf(SoundConnectException.class);
        assertThat(catchThrowableOfType(() -> service.posts(user,profileId,EventIntentPeriod.ALL,0,20),SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND);
    }
    @Test void ghostCanChangeOnlyIntentOfRetainedPublicationAndStandardRestoresUpdatedBadge() {
        service.update(user,eventId,update(EventIntent.THINKING,true,"Original note",0));
        profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
        var changed=service.update(user,eventId,update(EventIntent.GOING,true,"Original note",1));
        assertThat(changed.intent()).isEqualTo(EventIntent.GOING);
        assertThat(changed.publishedOnProfile()).isTrue(); assertThat(changed.publicationVisible()).isFalse();
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.THINKING,true,"Edited note",2)))
                .isInstanceOf(SoundConnectException.class);
        profile.setVisibilityMode(ListenerVisibilityMode.STANDARD);
        assertThat(service.get(user,eventId).publicationVisible()).isTrue();
        assertThat(service.get(user,eventId).intent()).isEqualTo(EventIntent.GOING);
    }
    @Test void malformedLegacyScheduleIsUnavailableAndNeverPermitsNewIntent() {
        service.update(user,eventId,update(EventIntent.GOING,false,null,0));
        when(cards.present(anyList())).thenReturn(List.of(event(null,null)));
        var state=service.get(user,eventId);
        assertThat(state.eventAvailable()).isFalse(); assertThat(state.event()).isNull();
        assertThat(state.canSetIntent()).isFalse(); assertThat(state.canPublish()).isFalse();
        assertThat(EventAudienceService.ended(event(null,null),now)).isTrue();
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.THINKING,false,null,1)))
                .isInstanceOf(SoundConnectException.class);
        assertThat(service.update(user,eventId,update(EventIntent.NONE,false,null,1)).intent()).isEqualTo(EventIntent.NONE);
    }
    @Test void musicianCanChooseThinkingAndGoingButCannotPublishNotesOrProfilePosts() {
        when(users.findRoleNamesByUserId(user)).thenReturn(Set.of("ROLE_MUSICIAN"));
        when(users.findExistingPersonalProfileRoleNames(user)).thenReturn(Set.of("ROLE_MUSICIAN"));
        assertThat(service.update(user,eventId,update(EventIntent.THINKING,false,null,0)).canPublish()).isFalse();
        assertThat(service.update(user,eventId,update(EventIntent.GOING,false,null,1)).intent()).isEqualTo(EventIntent.GOING);
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.GOING,true,null,2))).isInstanceOf(SoundConnectException.class);
        verify(repository,never()).listenerVisibility(any());
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_VENUE","ROLE_STUDIO","ROLE_PRODUCER","ROLE_ORGANIZER","ROLE_ADMIN","ROLE_USER"})
    void businessStaffOrMissingPersonalRoleCannotReadOrWriteAudiencePlans(String role) {
        when(users.findRoleNamesByUserId(user)).thenReturn(Set.of(role));
        assertThatThrownBy(() -> service.get(user,eventId)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.GOING,false,null,0))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(cards); verify(repository,never()).saveAndFlush(any());
    }
    @Test void inactiveAndConflictingOrMissingPersistedProfileFailClosed() {
        when(repository.lockActor(user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.GOING,false,null,0))).isInstanceOf(SoundConnectException.class);
        when(users.findExistingPersonalProfileRoleNames(user)).thenReturn(Set.of("ROLE_LISTENER","ROLE_MUSICIAN"));
        assertThatThrownBy(() -> service.get(user,eventId)).isInstanceOf(SoundConnectException.class);
        when(users.findExistingPersonalProfileRoleNames(user)).thenReturn(Set.of());
        assertThatThrownBy(() -> service.get(user,eventId)).isInstanceOf(SoundConnectException.class);
    }
    @Test void endedEventsRejectChangesButPermitUnpublishClearAndExactAcceptedRetry() {
        var original=update(EventIntent.GOING,true,"Before the event",0); service.update(user,eventId,original);
        when(clock.instant()).thenReturn(now.plusSeconds(7200));
        var ended=service.update(user,eventId,original); assertThat(ended.eventEnded()).isTrue(); assertThat(ended.canSetIntent()).isFalse();
        assertThat(ended.publicationVisible()).isTrue(); assertThat(ended.canPublish()).isFalse();
        assertThat(catchThrowableOfType(() -> service.update(user,eventId,update(EventIntent.THINKING,true,"Changed",1)),SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.EVENT_INTENT_CLOSED);
        service.update(user,eventId,update(EventIntent.GOING,false,null,1));
        assertThat(service.update(user,eventId,update(EventIntent.NONE,false,null,2)).intent()).isEqualTo(EventIntent.NONE);
    }
    @Test void unavailableEventHidesDetailsButOwnerCanClearStoredPlanAndUnknownEventDoesNotCreateTombstone() {
        service.update(user,eventId,update(EventIntent.GOING,true,"Note",0));
        when(cards.present(anyList())).thenReturn(List.of());
        var missing=service.get(user,eventId); assertThat(missing.event()).isNull(); assertThat(missing.eventAvailable()).isFalse();
        assertThat(missing.publicationVisible()).isFalse(); assertThat(missing.canSetIntent()).isFalse();
        assertThat(service.update(user,eventId,update(EventIntent.NONE,false,null,1)).intent()).isEqualTo(EventIntent.NONE);
        saved.set(null);
        assertThat(catchThrowableOfType(() -> service.get(user,eventId),SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND);
        assertThatThrownBy(() -> service.update(user,eventId,update(EventIntent.NONE,false,null,0))).isInstanceOf(SoundConnectException.class);
    }
    @Test void effectiveEndMatchesExistingOneHourFallbackAndExactIstanbulBoundary() {
        assertThat(EventAudienceService.ended(event(LocalTime.of(14,0),null),now)).isTrue();
        assertThat(EventAudienceService.ended(event(LocalTime.of(14,1),null),now)).isFalse();
        assertThat(EventAudienceService.ended(event(LocalTime.of(14,0),LocalTime.of(13,0)),now)).isTrue();
        assertThat(EventAudienceService.ended(event(LocalTime.of(14,0),LocalTime.of(15,0)),now)).isTrue();
    }
    @Test void noteValidationCountsUnicodeCodePointsAndRejectsHiddenActorFieldsOrInvalidStatesAtServiceBoundary() {
        String emoji="\ud83c\udfb5".repeat(500);
        assertThat(service.update(user,eventId,update(EventIntent.THINKING,true,emoji,0)).note()).isEqualTo(emoji);
        for (EventIntentUpdate invalid : List.of(update(EventIntent.GOING,true,emoji+"x",1),update(EventIntent.GOING,true,"nul\u0000",1),
                update(EventIntent.GOING,false,"private note",1),update(EventIntent.NONE,true,null,1),update(EventIntent.GOING,false,null,-1),
                update(EventIntent.GOING,false,null,Long.MAX_VALUE))) {
            assertThat(catchThrowableOfType(() -> service.update(user,eventId,invalid),SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.EVENT_INTENT_INVALID);
        }
    }
    @Test void invalidPageBoundsCannotQueryAnyPrivateOrPublicState() {
        for(int[] values:List.of(new int[]{-1,20},new int[]{1001,20},new int[]{0,0},new int[]{0,51})) {
            assertThatThrownBy(() -> service.mine(user,EventIntentPeriod.ALL,values[0],values[1])).isInstanceOf(SoundConnectException.class);
            assertThatThrownBy(() -> service.posts(user,profileId,EventIntentPeriod.ALL,values[0],values[1])).isInstanceOf(SoundConnectException.class);
        }
        verifyNoInteractions(repository,users,cards);
    }
}
