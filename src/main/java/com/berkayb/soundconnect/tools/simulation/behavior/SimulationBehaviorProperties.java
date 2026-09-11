package com.berkayb.soundconnect.tools.simulation.behavior;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded controls for the deterministic local population scheduler. */
@Validated
@ConfigurationProperties(prefix = "app.simulation.behavior")
public class SimulationBehaviorProperties {

	private boolean enabled = true;

	@Min(1_000)
	@Max(3_600_000)
	private long initialDelayMs = 30_000;

	@Min(1_000)
	@Max(3_600_000)
	private long tickDelayMs = 60_000;

	@Min(1)
	@Max(20)
	private int actionsPerTick = 4;

	@Min(300)
	@Max(2_000)
	private int plannedLikes = 1_200;

	@Min(100)
	@Max(1_000)
	private int plannedComments = 400;

	@Min(1)
	@Max(30)
	private int fastForwardDays = 7;

	@Min(1)
	@Max(100)
	private int actionsPerFastForwardDay = 24;

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public long getInitialDelayMs() { return initialDelayMs; }
	public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
	public long getTickDelayMs() { return tickDelayMs; }
	public void setTickDelayMs(long tickDelayMs) { this.tickDelayMs = tickDelayMs; }
	public int getActionsPerTick() { return actionsPerTick; }
	public void setActionsPerTick(int actionsPerTick) { this.actionsPerTick = actionsPerTick; }
	public int getPlannedLikes() { return plannedLikes; }
	public void setPlannedLikes(int plannedLikes) { this.plannedLikes = plannedLikes; }
	public int getPlannedComments() { return plannedComments; }
	public void setPlannedComments(int plannedComments) { this.plannedComments = plannedComments; }
	public int getFastForwardDays() { return fastForwardDays; }
	public void setFastForwardDays(int fastForwardDays) { this.fastForwardDays = fastForwardDays; }
	public int getActionsPerFastForwardDay() { return actionsPerFastForwardDay; }
	public void setActionsPerFastForwardDay(int actionsPerFastForwardDay) {
		this.actionsPerFastForwardDay = actionsPerFastForwardDay;
	}
}
