package com.berkayb.soundconnect.modules.instrument.seed;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
@ConditionalOnProperty(
		value = "app.instrument.catalog.seed.enabled",
		havingValue = "true",
		matchIfMissing = false
)
@RequiredArgsConstructor
@Slf4j
public class InstrumentCatalogSeeder implements ApplicationRunner {
	private static final String SEED_RESOURCE = "instrument-catalog-seed.json";

	private final InstrumentRepository instrumentRepository;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws Exception {
		List<String> instrumentNames = readSeed();
		validateSeed(instrumentNames);

		int created = 0;
		for (String rawName : instrumentNames) {
			String name = rawName.trim();
			if (instrumentRepository.findByNameIgnoreCase(name).isEmpty()) {
				instrumentRepository.save(Instrument.builder().name(name).build());
				created++;
			}
		}

		log.info(
				"[instrument-catalog-seed] synchronized catalogSize={} instrumentsCreated={}",
				instrumentNames.size(),
				created
		);
	}

	private List<String> readSeed() throws Exception {
		try (InputStream input = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
			return objectMapper.readValue(input, new TypeReference<>() {});
		}
	}

	private void validateSeed(List<String> instrumentNames) {
		if (instrumentNames == null || instrumentNames.isEmpty()) {
			throw new IllegalStateException("Instrument catalog seed cannot be empty");
		}

		Set<String> normalizedNames = new HashSet<>();
		for (String rawName : instrumentNames) {
			if (!StringUtils.hasText(rawName)) {
				throw new IllegalStateException("Instrument catalog seed contains a blank name");
			}
			String name = rawName.trim();
			if (name.length() > 80) {
				throw new IllegalStateException("Instrument catalog seed name is too long: " + name);
			}
			if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("Duplicate instrument catalog seed name: " + name);
			}
		}
	}
}
