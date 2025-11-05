package com.berkayb.soundconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.berkayb.soundconnect")
@EnableScheduling
public class SoundConnectApplication {
	
	public static void main(String[] args) {
		SpringApplication.run(SoundConnectApplication.class, args);
	}
	
}