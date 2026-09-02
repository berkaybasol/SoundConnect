package com.berkayb.soundconnect.modules.tablegroup.game.exception;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

/**
 * Signals the create-game conflict after any overdue active game has been reconciled.
 * The create transaction intentionally commits this exception so that a deadline
 * transition performed immediately before the conflict is not rolled back.
 */
public final class TableGroupGameActiveExistsException extends SoundConnectException {
	public TableGroupGameActiveExistsException() {
		super(ErrorType.TABLE_GROUP_GAME_ACTIVE_EXISTS);
	}
}
