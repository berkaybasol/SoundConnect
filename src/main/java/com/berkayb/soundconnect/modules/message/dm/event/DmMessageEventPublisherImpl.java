package com.berkayb.soundconnect.modules.message.dm.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class DmMessageEventPublisherImpl implements DmMessageEventPublisher{
	private final ApplicationEventPublisher applicationEventPublisher;
	
	
	@Override
	public void publishMessageSentEvent(DmMessageSentEvent event) {
		log.debug("Publishing DmMessageSentEvent: messageId={}, conversationId={}, senderId={}, recipientId={}",
		          event.getMessageId(), event.getConversationId(), event.getSenderId(), event.getRecipientId());
		applicationEventPublisher.publishEvent(event);
		
	}

	@Override
	public void publishMessageReadEvent(DmMessageReadEvent event) {
		log.debug("Publishing DmMessageReadEvent: conversationId={}, readerId={}",
		          event.conversationId(), event.readerId());
		applicationEventPublisher.publishEvent(event);
	}
}
