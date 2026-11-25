package com.berkayb.soundconnect.modules.tablegroup.chat.repository;

import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-${random.uuid};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TableGroupMessageRepositoryTest {
	
	@Autowired
	private TableGroupMessageRepository messageRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void bothSidesGetFullVisiblePagesAndCountsWhileAThirdViewerKeepsAllMessages() {
		UUID a = account("ayse"), b = account("deniz"), c = account("ece"), table = UUID.randomUUID();
		entityManager.getEntityManager().createNativeQuery(
				"insert into tbl_user_block (blocker_id, blocked_id, created_at) values (?1,?2,current_timestamp)")
				.setParameter(1, a).setParameter(2, b).executeUpdate();
		for (UUID sender : List.of(a, b, c, b, a, c)) {
			messageRepository.save(TableGroupMessage.builder().tableGroupId(table).senderId(sender)
					.createdAt(LocalDateTime.now()).messageType(MessageType.TEXT).content("text").build());
		}
		// A game card is redacted per viewer after paging; the shared game stays accessible.
		messageRepository.saveAndFlush(TableGroupMessage.builder().tableGroupId(table).senderId(b)
				.createdAt(LocalDateTime.now()).gameId(UUID.randomUUID()).messageType(MessageType.GAME).content("game").build());
		for (UUID viewer : List.of(a, b)) {
			var page = messageRepository.findVisibleHistory(table, viewer, PageRequest.of(0, 2));
			assertThat(page).hasSize(2);
			assertThat(page.getTotalElements()).isEqualTo(5);
			assertThat(messageRepository.findVisibleHistory(table, viewer, PageRequest.of(0, 20)))
					.allMatch(m -> m.getGameId() != null || !m.getSenderId().equals(viewer.equals(a) ? b : a));
		}
		assertThat(messageRepository.findVisibleHistory(table, c, PageRequest.of(0, 2)).getTotalElements()).isEqualTo(7);
	}

	private UUID account(String username) {
		var user = com.berkayb.soundconnect.modules.user.entity.User.builder().username(username)
				.email(username+"@test.invalid").password("unused").emailVerified(true)
				.status(com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE).build();
		return entityManager.persistAndFlush(user).getId();
	}
	
	@Test
	void findNewestMessages_whenMessagesExist_shouldReturnNonDeletedInDescendingOrder() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID otherTableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		
		LocalDateTime base = LocalDateTime.now().minusMinutes(10);
		
		TableGroupMessage msg1 = TableGroupMessage.builder()
		                                          
		                                          .tableGroupId(tableGroupId)
		                                          .senderId(senderId)
		                                          .content("msg1")
		                                          .messageType(MessageType.TEXT)
		                                          .createdAt(base.plusMinutes(1))
		                                          .deletedAt(null)
		                                          .build();
		
		TableGroupMessage msg2 = TableGroupMessage.builder()
		                                          
		                                          .tableGroupId(tableGroupId)
		                                          .senderId(senderId)
		                                          .content("msg2")
		                                          .messageType(MessageType.TEXT)
		                                          .createdAt(base.plusMinutes(2))
		                                          .deletedAt(null)
		                                          .build();
		
		// bu mesaj aynı gruba ait ama soft-delete edilmiş, dönmemesi lazım
		TableGroupMessage deletedMsg = TableGroupMessage.builder()
		                                                
		                                                .tableGroupId(tableGroupId)
		                                                .senderId(senderId)
		                                                .content("deleted")
		                                                .messageType(MessageType.TEXT)
		                                                .createdAt(base.plusMinutes(3))
		                                                .deletedAt(base.plusMinutes(5))
		                                                .build();
		
		// bu mesaj başka bir tableGroup'a ait, o yüzden dönmemeli
		TableGroupMessage otherGroupMsg = TableGroupMessage.builder()
		                                                   
		                                                   .tableGroupId(otherTableGroupId)
		                                                   .senderId(senderId)
		                                                   .content("other-group")
		                                                   .messageType(MessageType.TEXT)
		                                                   .createdAt(base.plusMinutes(4))
		                                                   .deletedAt(null)
		                                                   .build();
		
		messageRepository.saveAll(List.of(msg1, msg2, deletedMsg, otherGroupMsg));
		
		Pageable pageable = PageRequest.of(0, 10);
		
		// when
		Page<TableGroupMessage> page =
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
						tableGroupId,
						pageable
				);
		
		// then
		assertThat(page).isNotNull();
		assertThat(page.getTotalElements()).isEqualTo(2);
		
		List<TableGroupMessage> content = page.getContent();
		assertThat(content).hasSize(2);
		
		// Newest page semantics: msg2, msg1.
		assertThat(content.get(0).getContent()).isEqualTo("msg2");
		assertThat(content.get(1).getContent()).isEqualTo("msg1");
		
		// hiçbiri soft-deleted olmamalı
		assertThat(content)
				.allMatch(m -> m.getDeletedAt() == null);
		
		// hepsi aynı tableGroupId'e ait olmalı
		assertThat(content)
				.allMatch(m -> m.getTableGroupId().equals(tableGroupId));
	}
	
	@Test
	void findNewestMessages_whenNoMessages_shouldReturnEmptyPage() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 10);
		
		// when
		Page<TableGroupMessage> page =
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
						tableGroupId,
						pageable
				);
		
		// then
		assertThat(page).isNotNull();
		assertThat(page.getTotalElements()).isEqualTo(0);
		assertThat(page.getContent()).isEmpty();
	}

	@Test
	void findByClientMessageId_shouldBeScopedToTableAndSender() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		TableGroupMessage message = messageRepository.saveAndFlush(
				TableGroupMessage.builder()
						.tableGroupId(tableGroupId)
						.senderId(senderId)
						.clientMessageId(clientMessageId)
						.content("idempotent")
						.messageType(MessageType.TEXT)
						.createdAt(LocalDateTime.now())
						.build()
		);

		assertThat(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, senderId, clientMessageId)).contains(message);
		assertThat(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				tableGroupId, UUID.randomUUID(), clientMessageId)).isEmpty();
		assertThat(messageRepository.findByTableGroupIdAndSenderIdAndClientMessageId(
				UUID.randomUUID(), senderId, clientMessageId)).isEmpty();
	}

	@Test
	void findNewestMessages_pageZeroShouldContainLatestWindow() {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);
		List<TableGroupMessage> messages = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			messages.add(TableGroupMessage.builder()
					.tableGroupId(tableGroupId)
					.senderId(senderId)
					.content("msg-" + index)
					.messageType(MessageType.TEXT)
					.createdAt(base.plusMinutes(index))
					.build());
		}
		messageRepository.saveAllAndFlush(messages);

		// @CreatedDate may replace fixture values during persist, so fix the stored
		// timestamps only after auditing has run.
		for (int index = 0; index < messages.size(); index++) {
			entityManager.getEntityManager()
					.createQuery("""
							update TableGroupMessage message
							set message.createdAt = :createdAt
							where message.id = :id
							""")
					.setParameter("createdAt", base.plusMinutes(index))
					.setParameter("id", messages.get(index).getId())
					.executeUpdate();
		}
		entityManager.clear();

		Page<TableGroupMessage> firstPage =
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
						tableGroupId,
						PageRequest.of(0, 2)
				);
		Page<TableGroupMessage> secondPage =
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
						tableGroupId,
						PageRequest.of(1, 2)
				);

		assertThat(firstPage.getContent())
				.extracting(TableGroupMessage::getContent)
				.containsExactly("msg-4", "msg-3");
		assertThat(secondPage.getContent())
				.extracting(TableGroupMessage::getContent)
				.containsExactly("msg-2", "msg-1");
	}
}
