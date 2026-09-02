package com.berkayb.soundconnect.modules.tablegroup.chat.dto.request;

import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TableGroupMessageRequestDto(
		// mesajin govdesi
		@NotBlank(message = "Mesaj icerigi bos olamaz")
		@Size(max = 1000, message = "Mesaj en fazla 1000 karakter olabilir")
		String content,
		
		// mesaj tipi (varsayilan text)
		MessageType messageType,

		// Her kullanici TEXT gonderiminin zorunlu idempotency anahtari.
		@NotNull(message = "clientMessageId zorunludur")
		UUID clientMessageId
) {}
