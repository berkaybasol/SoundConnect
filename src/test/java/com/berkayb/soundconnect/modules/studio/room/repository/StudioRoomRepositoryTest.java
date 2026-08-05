package com.berkayb.soundconnect.modules.studio.room.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sc-room-repo-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class StudioRoomRepositoryTest {

    @Autowired private StudioRoomRepository roomRepository;
    @Autowired private StudioProfileRepository studioProfileRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CityRepository cityRepository;

    @Test
    void profileScopedPessimisticLookupNeverReturnsAnotherStudiosRoom() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        City city = cityRepository.save(City.builder().name("Room City " + suffix).build());
        StudioProfile ownerStudio = studioProfileRepository.save(profile(
                user("room-owner-" + suffix, city), "Owner Studio"
        ));
        StudioProfile otherStudio = studioProfileRepository.save(profile(
                user("room-other-" + suffix, city), "Other Studio"
        ));
        StudioRoom room = roomRepository.saveAndFlush(StudioRoom.builder()
                .studioProfile(ownerStudio)
                .slotIndex(0)
                .clientRequestId(UUID.randomUUID())
                .creationPayloadHash("0".repeat(64))
                .name("A Odasi")
                .capacity(4)
                .minimumCapacity(4)
                .currency("TRY")
                .reservationApprovalRequired(true)
                .build());

        assertThat(roomRepository.findByIdAndStudioProfileIdForUpdate(
                room.getId(), ownerStudio.getId()
        )).contains(room);
        assertThat(roomRepository.findByIdAndStudioProfileIdForUpdate(
                room.getId(), otherStudio.getId()
        )).isEmpty();
        assertThat(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(
                room.getId(), ownerStudio.getId()
        )).contains(room);
        assertThat(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(
                room.getId(), otherStudio.getId()
        )).isEmpty();
        assertThat(roomRepository.findActiveByIdAndStudioProfileId(
                room.getId(), ownerStudio.getId()
        )).contains(room);
        assertThat(roomRepository.findActiveByIdAndStudioProfileId(
                room.getId(), otherStudio.getId()
        )).isEmpty();
    }

    private StudioProfile profile(User owner, String name) {
        return StudioProfile.builder()
                .user(owner)
                .name(name)
                .timeZone("Europe/Istanbul")
                .build();
    }

    private User user(String username, City city) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.test")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .city(city)
                .build());
    }
}
