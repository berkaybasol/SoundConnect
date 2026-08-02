package com.berkayb.soundconnect.modules.studio.equipment.repository;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipment;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentDay;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sc-equipment-repo-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class StudioEquipmentRepositoryTest {

    @Autowired private StudioEquipmentRepository equipmentRepository;
    @Autowired private StudioEquipmentDayRepository dayRepository;
    @Autowired private BacklineCategoryRepository categoryRepository;
    @Autowired private StudioProfileRepository studioProfileRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CityRepository cityRepository;

    private StudioProfile studio;
    private BacklineCategory proAudio;
    private BacklineCategory microphones;
    private StudioEquipment microphone;
    private StudioEquipment amplifier;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        City city = cityRepository.save(City.builder().name("Equipment City " + suffix).build());
        User owner = userRepository.save(User.builder()
                .username("equipment-owner-" + suffix)
                .email("equipment-owner-" + suffix + "@example.test")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .city(city)
                .build());
        studio = studioProfileRepository.save(StudioProfile.builder()
                .user(owner)
                .name("Repository Test Studio")
                .timeZone("Europe/Istanbul")
                .build());

        proAudio = categoryRepository.save(BacklineCategory.createRoot(
                "pro-audio-" + suffix,
                "Pro Audio & Studyo",
                "pro audio & studyo " + suffix,
                "pro-audio",
                0
        ));
        microphones = categoryRepository.save(BacklineCategory.createChild(
                proAudio,
                "microphones-" + suffix,
                "Mikrofonlar",
                "mikrofonlar " + suffix,
                0
        ));
        BacklineCategory instruments = categoryRepository.save(BacklineCategory.createRoot(
                "instruments-" + suffix,
                "Enstrumanlar",
                "enstrumanlar " + suffix,
                "instruments",
                1
        ));
        BacklineCategory guitarAmplifiers = categoryRepository.save(BacklineCategory.createChild(
                instruments,
                "guitar-amplifiers-" + suffix,
                "Gitar Amfileri",
                "gitar amfileri " + suffix,
                0
        ));

        microphone = equipmentRepository.save(equipment(microphones, "Shure SM58", 2));
        amplifier = equipmentRepository.save(equipment(guitarAmplifiers, "Marshall DSL40", 3));
        equipmentRepository.save(equipment(microphones, "Sennheiser E835", 1));

        today = LocalDate.of(2026, 8, 10);
        StudioEquipmentDay microphoneDay = new StudioEquipmentDay(microphone, today);
        microphoneDay.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.BUSY, 1, 2);
        microphoneDay.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.MAINTENANCE, 1, 2);
        StudioEquipmentDay amplifierDay = new StudioEquipmentDay(amplifier, today);
        amplifierDay.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.BUSY, 1, 3);
        dayRepository.saveAllAndFlush(List.of(microphoneDay, amplifierDay));
    }

    @Test
    void listSupportsCategorySearchNonExclusiveAvailabilityBucketsAndCorrectCount() {
        var rootCategoryResult = equipmentRepository.findActiveByStudio(
                studio.getId(), "", proAudio.getId(), "ALL", today, page(20)
        );
        assertThat(rootCategoryResult.getContent())
                .extracting(StudioEquipment::getName)
                .containsExactly("Sennheiser E835", "Shure SM58");

        var leafCategoryResult = equipmentRepository.findActiveByStudio(
                studio.getId(), "", microphones.getId(), "ALL", today, page(20)
        );
        assertThat(leafCategoryResult.getContent())
                .extracting(StudioEquipment::getName)
                .containsExactly("Sennheiser E835", "Shure SM58");

        var categorySearchResult = equipmentRepository.findActiveByStudio(
                studio.getId(), "PRO AUDIO", null, "ALL", today, page(1)
        );
        assertThat(categorySearchResult.getTotalElements()).isEqualTo(2);
        assertThat(categorySearchResult.getContent())
                .extracting(StudioEquipment::getName)
                .containsExactly("Sennheiser E835");

        var busyPage = equipmentRepository.findActiveByStudio(
                studio.getId(), "", null, EquipmentAvailabilityBucket.BUSY.name(), today, page(1)
        );
        assertThat(busyPage.getTotalElements()).isEqualTo(2);

        var maintenanceResult = equipmentRepository.findActiveByStudio(
                studio.getId(), "", null, EquipmentAvailabilityBucket.MAINTENANCE.name(), today, page(20)
        );
        assertThat(maintenanceResult.getContent()).containsExactly(microphone);

        var availableResult = equipmentRepository.findActiveByStudio(
                studio.getId(), "", null, EquipmentAvailabilityBucket.AVAILABLE.name(), today, page(20)
        );
        assertThat(availableResult.getContent())
                .extracting(StudioEquipment::getName)
                .containsExactly("Marshall DSL40", "Sennheiser E835");
    }

    @Test
    void forwardMaximumAndProjectionCleanupUseTheSameRetentionBoundary() {
        StudioEquipmentDay expired = new StudioEquipmentDay(amplifier, today.minusDays(1));
        expired.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.MAINTENANCE, 3, 3);
        StudioEquipmentDay future = new StudioEquipmentDay(amplifier, today.plusDays(1));
        future.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.BUSY, 2, 3);
        dayRepository.saveAllAndFlush(List.of(expired, future));

        assertThat(dayRepository.findMaximumAllocatedQuantityFromDate(amplifier.getId(), today))
                .isEqualTo(2);
        assertThat(dayRepository.deleteBeforeDate(amplifier.getId(), today)).isEqualTo(1);
        assertThat(dayRepository.findRange(
                amplifier.getId(), today.minusDays(1), today.minusDays(1)
        )).isEmpty();
        assertThat(dayRepository.findRange(amplifier.getId(), today, today.plusDays(1)))
                .hasSize(2);
    }

    private StudioEquipment equipment(BacklineCategory leafCategory, String name, int totalQuantity) {
        return StudioEquipment.create(
                studio,
                leafCategory,
                UUID.randomUUID(),
                "0".repeat(64),
                name,
                null,
                null,
                null,
                totalQuantity,
                List.of(),
                List.of()
        );
    }

    private PageRequest page(int size) {
        return PageRequest.of(0, size, Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id")));
    }
}
