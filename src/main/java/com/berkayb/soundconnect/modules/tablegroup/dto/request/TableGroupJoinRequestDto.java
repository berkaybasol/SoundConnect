package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

public record TableGroupJoinRequestDto(
		@Size(max = 256, message = "Not en fazla 256 karakter olabilir")
		String note,
		@Size(max = 6) Set<@NotNull UUID> acknowledgedBlockedMemberIds
) {
	public TableGroupJoinRequestDto(String note) {
		this(note, null);
	}
}
