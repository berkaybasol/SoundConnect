package com.berkayb.soundconnect.shared.mail.dto;

import java.io.Serializable;

public record EmailVerificationMessage(
		String email,
		String token
) implements Serializable {}