package com.berkayb.soundconnect.shared.realtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketChannelsTest {

	@Test
	void brokerDestinationsUseRabbitCompatibleDotSeparatedRoutingKeys() {
		UUID id = UUID.fromString("11111111-2222-4333-8444-555555555555");

		assertThat(WebSocketChannels.notifications(id))
				.isEqualTo("/topic/notifications.11111111-2222-4333-8444-555555555555");
		assertThat(WebSocketChannels.notificationsBadge(id))
				.isEqualTo("/topic/notifications.11111111-2222-4333-8444-555555555555.badge");
		assertThat(WebSocketChannels.dm(id))
				.isEqualTo("/topic/dm.11111111-2222-4333-8444-555555555555");
		assertThat(WebSocketChannels.dmBadge(id))
				.isEqualTo("/topic/dm.11111111-2222-4333-8444-555555555555.badge");
		assertThat(WebSocketChannels.tableGroup(id))
				.isEqualTo("/topic/table-group.11111111-2222-4333-8444-555555555555");
		assertThat(WebSocketChannels.pulseRoom(id))
				.isEqualTo("/topic/pulse.11111111-2222-4333-8444-555555555555");
		assertThat(WebSocketChannels.pulseVote(id))
				.isEqualTo("/topic/pulse.11111111-2222-4333-8444-555555555555.vote");
		assertThat(WebSocketChannels.pulseState(id))
				.isEqualTo("/topic/pulse.11111111-2222-4333-8444-555555555555.state");
		assertThat(WebSocketChannels.pulsePresence(id))
				.isEqualTo("/topic/pulse.11111111-2222-4333-8444-555555555555.presence");

		List.of(
				WebSocketChannels.notifications(id),
				WebSocketChannels.notificationsBadge(id),
				WebSocketChannels.dm(id),
				WebSocketChannels.dmBadge(id),
				WebSocketChannels.tableGroup(id),
				WebSocketChannels.pulseRoom(id),
				WebSocketChannels.pulseVote(id),
				WebSocketChannels.pulseState(id),
				WebSocketChannels.pulsePresence(id)
		).forEach(destination -> assertThat(destination.substring("/topic/".length()))
				.as("Rabbit STOMP routing key in %s", destination)
				.doesNotContain("/"));
	}
}
