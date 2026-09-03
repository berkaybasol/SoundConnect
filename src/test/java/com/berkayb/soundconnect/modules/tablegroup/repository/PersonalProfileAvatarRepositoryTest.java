package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarCandidate;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false"
})
@Sql(statements = {
		"create table tbl_user (id uuid primary key, user_name varchar(255))",
		"create table tbl_musician_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)",
		"create table \"tbl_listener-profile\" (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid, visibility_mode varchar(16) default 'STANDARD')",
		"create table tbl_organizer_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)",
		"create table tbl_producer_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)",
		"create table tbl_studio_profile (id uuid primary key, user_id uuid unique, profile_picture_media_id uuid)"
})
class PersonalProfileAvatarRepositoryTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PersonalProfileAvatarRepository repository;

	@Test
	void findCandidatesByUserIdIn_shouldExecuteValidatedPersonalOnlyProjection() {
		UUID personalUserId = UUID.randomUUID();
		UUID studioOnlyUserId = UUID.randomUUID();
		UUID musicianMediaId = UUID.randomUUID();
		UUID listenerMediaId = UUID.randomUUID();
		UUID organizerMediaId = UUID.randomUUID();
		UUID producerMediaId = UUID.randomUUID();
		insertUser(personalUserId);
		insertUser(studioOnlyUserId);
		insertProfile("tbl_musician_profile", personalUserId, musicianMediaId);
		insertProfile("\"tbl_listener-profile\"", personalUserId, listenerMediaId);
		jdbcTemplate.update(
				"update \"tbl_listener-profile\" set visibility_mode = 'GHOST' where user_id = ?",
				personalUserId
		);
		insertProfile("tbl_organizer_profile", personalUserId, organizerMediaId);
		insertProfile("tbl_producer_profile", personalUserId, producerMediaId);
		insertProfile("tbl_studio_profile", studioOnlyUserId, UUID.randomUUID());

		List<PersonalProfileAvatarCandidate> result = repository.findCandidatesByUserIdIn(
				List.of(personalUserId, studioOnlyUserId));

		assertThat(result).hasSize(2);
		PersonalProfileAvatarCandidate personal = result.stream()
				.filter(candidate -> candidate.userId().equals(personalUserId))
				.findFirst()
				.orElseThrow();
		assertThat(personal.musicianMediaId()).isEqualTo(musicianMediaId);
		assertThat(personal.listenerMediaId()).isEqualTo(listenerMediaId);
		assertThat(personal.organizerMediaId()).isEqualTo(organizerMediaId);
		assertThat(personal.producerMediaId()).isEqualTo(producerMediaId);
		assertThat(personal.username()).isEqualTo("user-" + personalUserId);
		assertThat(personal.listenerVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);

		PersonalProfileAvatarCandidate studioOnly = result.stream()
				.filter(candidate -> candidate.userId().equals(studioOnlyUserId))
				.findFirst()
				.orElseThrow();
		assertThat(studioOnly.musicianMediaId()).isNull();
		assertThat(studioOnly.listenerMediaId()).isNull();
		assertThat(studioOnly.organizerMediaId()).isNull();
		assertThat(studioOnly.producerMediaId()).isNull();
	}

	private void insertUser(UUID userId) {
		jdbcTemplate.update(
				"insert into tbl_user (id, user_name) values (?, ?)",
				userId,
				"user-" + userId
		);
	}

	private void insertProfile(String tableName, UUID userId, UUID mediaId) {
		jdbcTemplate.update(
				"insert into " + tableName
						+ " (id, user_id, profile_picture_media_id) values (?, ?, ?)",
				UUID.randomUUID(),
				userId,
				mediaId
		);
	}
}
