package com.berkayb.soundconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.berkayb.soundconnect")
public class SoundConnectApplication {
	
	public static void main(String[] args) {
		SpringApplication.run(SoundConnectApplication.class, args);
	}
	
}