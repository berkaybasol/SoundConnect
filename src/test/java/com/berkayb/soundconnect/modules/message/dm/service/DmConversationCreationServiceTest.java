package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.model.DmParticipantPair;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DmConversationCreationServiceTest {
    @Test void existingSharedHistoryRemainsReachableWithoutCreatingOrSendingToErasedPeer() {
        var repository=mock(DMConversationRepository.class); var accounts=mock(AccountDeliveryFence.class);
        var service=new DmConversationCreationService(repository,accounts);
        UUID actor=UUID.randomUUID(), erased=UUID.randomUUID(), conversation=UUID.randomUUID();
        var pair=DmParticipantPair.of(actor,erased);
        when(repository.findConversationBetweenUsers(pair.userAId(),pair.userBId()))
                .thenReturn(Optional.of(DMConversation.builder().id(conversation).build()));
        assertThat(service.getOrCreate(actor,pair)).isEqualTo(conversation);
        verify(accounts).requireConversationReader(actor,List.of(pair.userAId(),pair.userBId()));
        verify(accounts,never()).requireActive(any());
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void absentConversationCannotBeCreatedWithErasedPeer() {
        var repository=mock(DMConversationRepository.class); var accounts=mock(AccountDeliveryFence.class);
        var service=new DmConversationCreationService(repository,accounts);
        UUID actor=UUID.randomUUID(); var pair=DmParticipantPair.of(actor,UUID.randomUUID());
        doThrow(new SoundConnectException(ErrorType.ACCOUNT_DELETED)).when(accounts).requireActive(any());
        assertThat(catchThrowableOfType(() -> service.getOrCreate(actor,pair),SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.ACCOUNT_DELETED);
        verify(repository,never()).saveAndFlush(any());
    }
}
