package com.berkayb.soundconnect.modules.pulse.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "pulse")
public class PulseProperties {
	
	@Min(250) // 250ms altı scheduler spam olur
	private long lifecycleTickIntervalMs = 5000;
	
	@Min(1)
	private int roomDurationMinutes = 30;
	
	@Valid
	@NotNull
	private Rooms rooms = new Rooms();
	
	@Valid
	@NotNull
	private Cooldown cooldown = new Cooldown();
	
	@NotEmpty
	private List<String> defaultTopics = List.of("Gündem Serbest");
	
	@Data
	public static class Rooms {
		@Min(1)
		private int count = 10;
	}
	
	@Data
	public static class Cooldown {
		@Min(1)
		private int seconds = 3;
	}
}