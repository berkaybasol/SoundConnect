package com.berkayb.soundconnect.modules.user.enums;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;

public enum City {
	ISTANBUL,
	TEKIRDAG,
	EDIRNE,
	KIRKLARELI,
	CANAKKALE,
	BALIKESIR,
	BURSA,
	YALOVA,
	KOCAELI,
	SAKARYA,
	BILECIK,
	
	// Ege Bölgesi
	IZMIR,
	MANISA,
	AYDIN,
	DENIZLI,
	MUGLA,
	KUTAHYA,
	UŞAK,
	AFYONKARAHISAR,
	
	// Akdeniz Bölgesi
	ANTALYA,
	ADANA,
	MERSIN,
	HATAY,
	ISPARTA,
	BURDUR,
	KAHRAMANMARAS,
	OSMANIYE,
	
	// İç Anadolu Bölgesi
	ANKARA,
	KONYA,
	KAYSERI,
	ESKISEHIR,
	SIVAS,
	AKSARAY,
	KIRIKKALE,
	KARAMAN,
	NIGDE,
	NEVSEHIR,
	YOZGAT,
	CANKIRI,
	
	// Karadeniz Bölgesi
	ZONGULDAK,
	BARTIN,
	KARABUK,
	BOLU,
	DÜZCE,
	AMASYA,
	ORDU,
	GİRESUN,
	SAMSUN,
	SINOP,
	TOKAT,
	TRABZON,
	RIZE,
	ARTVIN,
	BAYBURT,
	GUMUSHANE,
	
	// Doğu Anadolu Bölgesi
	ERZURUM,
	ERZINCAN,
	AĞRI,
	KARS,
	MALATYA,
	ELAZIG,
	TUNCELI,
	VAN,
	MUŞ,
	BINGOL,
	BITLIS,
	HAKKARI,
	IGDIR,
	ARDAHAN,
	
	// Güneydoğu Anadolu Bölgesi
	DIYARBAKIR,
	SANLIURFA,
	MARDIN,
	BATMAN,
	SIIRT,
	SIRNAK,
	GAZIANTEP,
	ADIYAMAN,
	KILIS;
	
	@JsonCreator
	public static City fromJson(String value) {
		String normalized = value
				.toUpperCase()
				.replace("Ç", "C")
				.replace("Ğ", "G")
				.replace("İ", "I")
				.replace("Ö", "O")
				.replace("Ş", "S")
				.replace("Ü", "U");
		
		return Arrays.stream(City.values())
		             .filter(city -> city.name().equals(normalized))
		             .findFirst()
		             .orElseThrow(() -> new SoundConnectException(ErrorType.INVALID_CITY_NAME));
	}
}