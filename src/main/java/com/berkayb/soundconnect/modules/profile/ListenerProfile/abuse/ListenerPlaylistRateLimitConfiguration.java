package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ListenerPlaylistRateLimitProperties.class)
public class ListenerPlaylistRateLimitConfiguration {
}
