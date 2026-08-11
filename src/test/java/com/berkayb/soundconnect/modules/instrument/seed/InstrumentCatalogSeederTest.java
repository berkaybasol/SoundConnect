package com.berkayb.soundconnect.modules.instrument.seed;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentCatalogSeederTest {
	@Mock
	private InstrumentRepository instrumentRepository;

	@Test
	void createsTheCompleteMusicIndustryCatalog() throws Exception {
		when(instrumentRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
		InstrumentCatalogSeeder seeder = new InstrumentCatalogSeeder(instrumentRepository, new ObjectMapper());

		seeder.run(null);

		ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
		verify(instrumentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
		List<String> names = captor.getAllValues().stream().map(Instrument::getName).toList();
		assertThat(names)
				.hasSizeGreaterThanOrEqualTo(100)
				.contains(
						"Bas Gitar",
						"Bateri",
						"Bağlama",
						"Vokal",
						"DJ",
						"Ses Mühendisi",
						"Prodüktör",
						"Turntable",
						"MIDI Klavye",
						"DAW / Bilgisayar",
						"Mix Mühendisi",
						"Mastering Mühendisi"
				);
		assertThat(names.stream().map(name -> name.toLowerCase(Locale.ROOT)).distinct().count())
				.isEqualTo(names.size());
	}

	@Test
	void keepsExistingRowsAndCreatesNothingOnTheNextStartup() throws Exception {
		when(instrumentRepository.findByNameIgnoreCase(anyString()))
				.thenAnswer(invocation -> Optional.of(Instrument.builder()
						.name(invocation.getArgument(0, String.class))
						.build()));
		InstrumentCatalogSeeder seeder = new InstrumentCatalogSeeder(instrumentRepository, new ObjectMapper());

		seeder.run(null);

		verify(instrumentRepository, never()).save(org.mockito.ArgumentMatchers.any(Instrument.class));
	}
}
