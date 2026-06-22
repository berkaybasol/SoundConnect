package com.berkayb.soundconnect.modules.promotion.enums;

// promotioonlarin sistem icindeki yayin durumunu tanimlar
public enum PromotionStatus {
	DRAFT, // icerik olusturulmus ancak henuz yayina alinmamistir admin taslak olarak hazirlayip daha sonra yayinlayabilir
	ACTIVE,
	INACTIVE,
	EXPIRED
}