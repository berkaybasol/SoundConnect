package com.berkayb.soundconnect.shared.mail.dto;

import java.io.Serializable;

// Email dogrulama mesaji icin kullanilan DTO
// RabbitMQ' uzerinden Producer -> Consumer'a tasinan verinin yapisinin belirler.
public record EmailVerificationMessage(
		String email,
		String token
) implements Serializable {} // Serializable mesajin RabbitMQ ile JSON olarak tasinabilmesi icin zorunludur.