package com.berkayb.soundconnect.modules.location.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurkishAlphabeticalOrderTest {

	@Test
	void sortsNamesByTheTurkishAlphabet() {
		List<String> names = new ArrayList<>(List.of(
				"Üsküdar",
				"100. Yıl",
				"İstanbul",
				"İzmir",
				"Iğdır",
				"Isparta",
				"Şişli",
				"Sivas",
				"10 Ekim",
				"Çankaya",
				"Ceyhan",
				"Ödemiş",
				"Osmangazi",
				"Gölbaşı",
				"Gazipaşa",
				"Ğazi",
				"Ordu",
				"Ağrı",
				"Uşak",
				"2 Eylül",
				"Adana"
		));

		names.sort(TurkishAlphabeticalOrder.textComparator());

		assertThat(names).containsExactly(
				"2 Eylül",
				"10 Ekim",
				"100. Yıl",
				"Adana",
				"Ağrı",
				"Ceyhan",
				"Çankaya",
				"Gazipaşa",
				"Gölbaşı",
				"Ğazi",
				"Iğdır",
				"Isparta",
				"İstanbul",
				"İzmir",
				"Ordu",
				"Osmangazi",
				"Ödemiş",
				"Sivas",
				"Şişli",
				"Uşak",
				"Üsküdar"
		);
	}
}
