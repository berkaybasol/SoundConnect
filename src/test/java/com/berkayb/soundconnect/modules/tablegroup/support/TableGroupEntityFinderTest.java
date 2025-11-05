package com.berkayb.soundconnect.modules.tablegroup.support;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TableGroupEntityFinderTest {
	
	@Mock
	private TableGroupRepository tableGroupRepository;
	
	@Mock
	private TableGroupMessageRepository tableGroupMessageRepository;
	
	@InjectMocks
	private TableGroupEntityFinder entityFinder;
	
	@Test
	void GetTableGroupByTableGroupId_whenExists_shouldReturnEntity() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		
		TableGroup tableGroup = new TableGroup();
		tableGroup.setId(tableGroupId);
		
		when(tableGroupRepository.findById(tableGroupId))
				.thenReturn(Optional.of(tableGroup));
		
		// when
		TableGroup result = entityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		// then
		assertThat(result).isSameAs(tableGroup);
		assertThat(result.getId()).isEqualTo(tableGroupId);
		
		verify(tableGroupRepository).findById(tableGroupId);
	}
	
	@Test
	void GetTableGroupByTableGroupId_whenNotExists_shouldThrowSoundConnectExceptionWithProperErrorType() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		
		when(tableGroupRepository.findById(tableGroupId))
				.thenReturn(Optional.empty());
		
		// when & then
		assertThatThrownBy(() -> entityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> {
					SoundConnectException sce = (SoundConnectException) ex;
					assertThat(sce.getErrorType()).isEqualTo(ErrorType.TABLE_GROUP_NOT_FOUND);
				});
		
		verify(tableGroupRepository).findById(tableGroupId);
	}
}