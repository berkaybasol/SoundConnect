package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.model.DmParticipantPair;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DmConversationCreationService {
    private final DMConversationRepository conversations;
    private final AccountDeliveryFence accounts;

    @Transactional
    public UUID getOrCreate(UUID actorId, DmParticipantPair pair) {
        accounts.requireConversationReader(actorId,List.of(pair.userAId(),pair.userBId()));
        return conversations.findConversationBetweenUsers(pair.userAId(),pair.userBId()).map(DMConversation::getId)
                .orElseGet(() -> {
                    accounts.requireActive(List.of(pair.userAId(),pair.userBId()));
                    return conversations.saveAndFlush(DMConversation.builder()
                        .userAId(pair.userAId()).userBId(pair.userBId()).build()).getId();
                });
    }

    @Transactional
    public Optional<UUID> findExisting(UUID actorId, DmParticipantPair pair) {
        accounts.requireConversationReader(actorId,List.of(pair.userAId(),pair.userBId()));
        return conversations.findConversationBetweenUsers(pair.userAId(),pair.userBId()).map(DMConversation::getId);
    }
}
