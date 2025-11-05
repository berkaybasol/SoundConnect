package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationRepositoryIT – Sektör standardı JPA repository testleri.
 * Bu sürümde flakiness'i bitirmek için persist sonrası created_at'i
 * native SQL ile deterministik biçimde set ediyoruz.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.jpa.hibernate.ddl-auto=update",
		"spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true",
		"spring.flyway.enabled=false",
		"spring.liquibase.enabled=false",
		"SOUNDCONNECT_JWT_SECRETKEY=dummy",
		"app.jwt.secret=dummy"
})
class NotificationRepositoryIT {
	
	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("soundconnect_test")
					.withUsername("sc")
					.withPassword("sc");
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
	}
	
	@Autowired
	private NotificationRepository repo;
	
	@Autowired
	private EntityManager entityManager;
	
	private final UUID userA = UUID.randomUUID();
	private final UUID userB = UUID.randomUUID();
	
	@BeforeEach
	void cleanup() {
		repo.deleteAll();
		entityManager.clear();
	}
	
	/**
	 * Deterministik created_at ile Notification oluşturur:
	 * 1) save
	 * 2) native SQL ile created_at güncelle
	 * 3) persistence context'i temizle
	 */
	private Notification make(UUID user, NotificationType type, boolean read,
	                          String title, String msg, Map<String, Object> payload, LocalDateTime createdAt) {
		Notification n = Notification.builder()
		                             .recipientId(user)
		                             .type(type)
		                             .title(title)
		                             .message(msg)
		                             .payload(payload)
		                             .read(read)
		                             .build();
		
		// Önce persist et
		Notification saved = repo.save(n);
		entityManager.flush();
		
		// Ardından created_at'i native SQL ile hedef değere çek
		entityManager.createNativeQuery("UPDATE tbl_notification SET created_at = :ts WHERE id = :id")
		             .setParameter("ts", Timestamp.valueOf(createdAt))
		             .setParameter("id", saved.getId())
		             .executeUpdate();
		
		entityManager.flush();
		entityManager.clear(); // cache'teki eski entity'yi temizle
		
		// Tekrar yükleyip güncel created_at ile döndür
		return repo.findById(saved.getId()).orElseThrow();
	}
	
	@Test
	@DisplayName("CRUD + JSONB + findById – Temel fonksiyonlar")
	void saveAndFind_basicCrud_jsonbAndIndexes() {
		Map<String, Object> payload = Map.of("recipientEmail", "user@example.com", "x", 1);
		Notification saved = make(
				userA, NotificationType.MEDIA_UPLOAD_RECEVIED, false,
				"Yükleme alındı", "Mesaj", payload,
				LocalDateTime.of(2025, 9, 4, 12, 0, 0)
		);
		
		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getPayload()).containsEntry("recipientEmail", "user@example.com");
		
		Optional<Notification> byId = repo.findById(saved.getId());
		assertThat(byId).isPresent();
		assertThat(byId.get().getPayload()).containsEntry("x", 1);
	}
	
	@Test
	@DisplayName("findByRecipientIdOrderByCreatedAtDesc – Sıralama kesin!")
	void findByRecipient_sortedDesc() {
		LocalDateTime now = LocalDateTime.of(2025, 9, 4, 12, 0, 0);
		Notification n1 = make(userA, NotificationType.SOCIAL_NEW_FOLLOWER, false, "t1", "m1", Map.of(), now.minusMinutes(3));
		Notification n2 = make(userA, NotificationType.MEDIA_TRANSCODE_READY, true, "t2", "m2", Map.of(), now.minusMinutes(2));
		Notification n3 = make(userA, NotificationType.AUTH_EMAIL_VERIFIED, false, "t3", "m3", Map.of(), now.minusMinutes(1));
		
		var page = repo.findByRecipientIdOrderByCreatedAtDesc(userA, org.springframework.data.domain.PageRequest.of(0, 10));
		assertThat(page.getContent()).extracting("id")
		                             .containsExactly(n3.getId(), n2.getId(), n1.getId());
	}
	
	@Test
	@DisplayName("countByRecipientIdAndReadIsFalse – Sadece okunmamışlar")
	void countUnread_onlyFalseOnes() {
		make(userA, NotificationType.MEDIA_UPLOAD_RECEVIED, false, "u1", "", Map.of(), LocalDateTime.of(2025, 9, 4, 12, 1));
		make(userA, NotificationType.AUTH_EMAIL_VERIFIED, false, "u2", "", Map.of(), LocalDateTime.of(2025, 9, 4, 12, 2));
		
		long unread = repo.countByRecipientIdAndReadIsFalse(userA);
		assertThat(unread).isGreaterThanOrEqualTo(2L);
	}
	
	@Test
	@DisplayName("findTop10ByRecipientIdOrderByCreatedAtDesc – Limit ve sıralama")
	void findTop10ByRecipient_descOrder_limitApplies() {
		LocalDateTime base = LocalDateTime.of(2025, 9, 4, 13, 0, 0);
		for (int i = 0; i < 12; i++) {
			make(userB, NotificationType.MEDIA_UPLOAD_RECEVIED, false, "b" + i, "m" + i,
			     Map.of("i", i), base.minusSeconds(60 - i));
		}
		List<Notification> top10 = repo.findTop10ByRecipientIdOrderByCreatedAtDesc(userB);
		assertThat(top10).hasSize(10);
		assertThat(top10.get(0).getTitle()).isEqualTo("b11");
	}
	
	@Test
	@DisplayName("findByRecipientIdAndTypeInOrderByCreatedAtDesc – Tür filtresi")
	void findByTypes_filtering() {
		make(userA, NotificationType.MEDIA_TRANSCODE_FAILED, false, "f1", "", Map.of(), LocalDateTime.of(2025, 9, 4, 14, 0));
		var page = repo.findByRecipientIdAndTypeInOrderByCreatedAtDesc(
				userA,
				List.of(NotificationType.MEDIA_TRANSCODE_FAILED, NotificationType.AUTH_EMAIL_VERIFIED),
				org.springframework.data.domain.PageRequest.of(0, 20)
		);
		assertThat(page.getContent()).extracting(Notification::getType)
		                             .allMatch(t -> t == NotificationType.MEDIA_TRANSCODE_FAILED || t == NotificationType.AUTH_EMAIL_VERIFIED);
	}
	
	@Test
	@DisplayName("markAsRead – Sadece kendi kaydını okundu yapar")
	void markAsRead_updatesSingleOwned() {
		Notification unread = make(userA, NotificationType.SOCIAL_NEW_FOLLOWER, false, "x", "", Map.of(), LocalDateTime.of(2025, 9, 4, 15, 0));
		
		int updated = repo.markAsRead(unread.getId(), userA);
		assertThat(updated).isEqualTo(1);
		
		entityManager.clear();
		Optional<Notification> reloaded = repo.findById(unread.getId());
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().isRead()).isTrue();
		
		int notUpdated = repo.markAsRead(unread.getId(), userB);
		assertThat(notUpdated).isEqualTo(0);
	}
	
	@Test
	@DisplayName("markAllAsRead – Tüm unread’leri okundu yapar (sadece o user için)")
	void markAllAsRead_updatesOnlyUsersUnreads() {
		make(userB, NotificationType.MEDIA_TRANSCODE_READY, false, "ub1", "", Map.of(), LocalDateTime.of(2025, 9, 4, 15, 1));
		make(userB, NotificationType.MEDIA_TRANSCODE_READY, false, "ub2", "", Map.of(), LocalDateTime.of(2025, 9, 4, 15, 2));
		
		int updated = repo.markAllAsRead(userB);
		assertThat(updated).isGreaterThanOrEqualTo(2);
		
		entityManager.clear();
		long unreadAfter = repo.countByRecipientIdAndReadIsFalse(userB);
		assertThat(unreadAfter).isZero();
	}
	
	@Test
	@DisplayName("findByIdAndRecipientId – Güvenlik: başkası göremez")
	void findByIdAndRecipient_securityOwnership() {
		Notification n = make(userA, NotificationType.AUTH_RESET_PASSWORD, false, "sec", "", Map.of(), LocalDateTime.of(2025, 9, 4, 16, 0));
		assertThat(repo.findByIdAndRecipientId(n.getId(), userA)).isPresent();
		assertThat(repo.findByIdAndRecipientId(n.getId(), userB)).isEmpty();
	}
}