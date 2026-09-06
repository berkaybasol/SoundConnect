package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MusicianCalendarRateLimitProperties.class)
public class MusicianCalendarRateLimitConfiguration {}
