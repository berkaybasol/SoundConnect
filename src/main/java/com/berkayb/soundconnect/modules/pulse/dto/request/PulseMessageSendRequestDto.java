package com.berkayb.soundconnect.modules.pulse.dto.request;

import java.util.UUID;

/**
 * Client'in WebSocket uzerinden gonderdigi Pulse mesajini temsil eder
 * - Sadece oda bilgisini ve mesaj icerigini tasir
 * - Kimlik bilgisi (userId) WebSocket oturumundan cekilir. clienttan gelmez
 */
public record PulseMessageSendRequestDto(
		
		UUID roomId, // mesajin gonderildigi odanin idsi. Client subscribe oldugu roomId'yi buraya gonderir.
		
		String content // mesaj icerigi
) {
}