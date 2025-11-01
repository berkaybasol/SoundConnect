package com.berkayb.soundconnect.modules.tablegroup.support;

import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TableGroupEntityFinder {
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupMessageRepository tableGroupMessageRepository;
	
	public TableGroup GetTableGroupByTableGroupId(UUID tableGroupId) {
		return tableGroupRepository.findById(tableGroupId)
		                                            .orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
	}
}