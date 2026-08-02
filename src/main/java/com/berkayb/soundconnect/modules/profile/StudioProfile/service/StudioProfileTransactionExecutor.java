package com.berkayb.soundconnect.modules.profile.StudioProfile.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Opens the short database transaction only after external profile metadata is
 * resolved. A separate Spring bean is required because self-invocation would
 * bypass transaction interception.
 */
@Component
public class StudioProfileTransactionExecutor {

	@Transactional
	public <T> T execute(Supplier<T> operation) {
		return Objects.requireNonNull(operation, "operation").get();
	}
}
