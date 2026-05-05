package com.berkayb.soundconnect.shared.mail.enums;


// Mail turlerinin merkezi enum sinifi. hangi mailin hangi amacla gonderildigini ayirir
public enum MailKind {
	OTP,
	NOTIFICATION,
	PASSWORD_RESET,
	VENUE_APPLICATION_ADMIN,
	CAMPAIGN, // ilerde pazarlama/ kampanya promosyon icin
	GENERIC // baska turler destek vs.
}
