package com.berkayb.soundconnect.modules.tablegroup.chat.repository;

import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@DataJpaTest
class TableGroupMessageRepositoryTest {
	
	@Autowired
	private TableGroupMessageRepository messageRepository;
	
	@Test
	void findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc_whenMessagesExist_shouldReturnNonDeletedInAscendingOrder() {
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
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(tableGroupId, pageable);
		
		// then
		assertThat(page).isNotNull();
		assertThat(page.getTotalElements()).isEqualTo(2);
		
		List<TableGroupMessage> content = page.getContent();
		assertThat(content).hasSize(2);
		
		// createdAt ASC: msg1, msg2 sırayla gelmeli
		assertThat(content.get(0).getContent()).isEqualTo("msg1");
		assertThat(content.get(1).getContent()).isEqualTo("msg2");
		
		// hiçbiri soft-deleted olmamalı
		assertThat(content)
				.allMatch(m -> m.getDeletedAt() == null);
		
		// hepsi aynı tableGroupId'e ait olmalı
		assertThat(content)
				.allMatch(m -> m.getTableGroupId().equals(tableGroupId));
	}
	
	@Test
	void findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc_whenNoMessages_shouldReturnEmptyPage() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 10);
		
		// when
		Page<TableGroupMessage> page =
				messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(tableGroupId, pageable);
		
		// then
		assertThat(page).isNotNull();
		assertThat(page.getTotalElements()).isEqualTo(0);
		assertThat(page.getContent()).isEmpty();
	}
}