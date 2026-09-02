package com.berkayb.soundconnect.modules.tablegroup.abuse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.table-group.rate-limit")
public class TableGroupRateLimitProperties {

	private boolean enabled = true;

	@NotBlank
	private String keyPrefix = "soundconnect:table-group:rate-limit";

	@Valid @NotNull
	private Policy create = new Policy(10, Duration.ofHours(1));

	@Valid @NotNull
	private Policy join = new Policy(60, Duration.ofHours(1));

	@Valid @NotNull
	private Policy messagePerTable = new Policy(60, Duration.ofMinutes(1));

	@Valid @NotNull
	private Policy messageGlobal = new Policy(120, Duration.ofMinutes(1));

	@Valid @NotNull
	private Policy messageTableGlobal = new Policy(120, Duration.ofMinutes(1));

	@Valid @NotNull
	private Policy gameCreate = new Policy(10, Duration.ofHours(1));

	@Valid @NotNull
	private Policy gameCommand = new Policy(60, Duration.ofMinutes(1));

	@Valid @NotNull
	private Policy gameRead = new Policy(120, Duration.ofMinutes(1));

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getKeyPrefix() { return keyPrefix; }
	public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
	public Policy getCreate() { return create; }
	public void setCreate(Policy create) { this.create = create; }
	public Policy getJoin() { return join; }
	public void setJoin(Policy join) { this.join = join; }
	public Policy getMessagePerTable() { return messagePerTable; }
	public void setMessagePerTable(Policy messagePerTable) { this.messagePerTable = messagePerTable; }
	public Policy getMessageGlobal() { return messageGlobal; }
	public void setMessageGlobal(Policy messageGlobal) { this.messageGlobal = messageGlobal; }
	public Policy getMessageTableGlobal() { return messageTableGlobal; }
	public void setMessageTableGlobal(Policy messageTableGlobal) { this.messageTableGlobal = messageTableGlobal; }
	public Policy getGameCreate() { return gameCreate; }
	public void setGameCreate(Policy gameCreate) { this.gameCreate = gameCreate; }
	public Policy getGameCommand() { return gameCommand; }
	public void setGameCommand(Policy gameCommand) { this.gameCommand = gameCommand; }
	public Policy getGameRead() { return gameRead; }
	public void setGameRead(Policy gameRead) { this.gameRead = gameRead; }

	public static class Policy {
		@Min(1)
		private int limit;
		@NotNull
		private Duration window;

		public Policy() { }
		public Policy(int limit, Duration window) {
			this.limit = limit;
			this.window = window;
		}
		public int getLimit() { return limit; }
		public void setLimit(int limit) { this.limit = limit; }
		public Duration getWindow() { return window; }
		public void setWindow(Duration window) { this.window = window; }

		@AssertTrue(message = "table-group rate-limit window must be at least one second")
		public boolean isWindowValid() {
			return window != null && window.compareTo(Duration.ofSeconds(1)) >= 0;
		}
	}
}
