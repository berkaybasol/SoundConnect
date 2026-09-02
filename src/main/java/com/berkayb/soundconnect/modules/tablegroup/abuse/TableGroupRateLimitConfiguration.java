package com.berkayb.soundconnect.modules.tablegroup.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TableGroupRateLimitProperties.class)
public class TableGroupRateLimitConfiguration {
}
