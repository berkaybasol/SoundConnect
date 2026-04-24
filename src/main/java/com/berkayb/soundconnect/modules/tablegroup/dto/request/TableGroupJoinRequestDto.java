package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import jakarta.validation.constraints.Size;

public record TableGroupJoinRequestDto(
		@Size(max = 256, message = "Not en fazla 256 karakter olabilir")
		String note
) {
}