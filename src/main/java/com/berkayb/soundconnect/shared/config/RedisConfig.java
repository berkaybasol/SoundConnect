package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
	
	@Bean
	public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory cf) {
		RedisTemplate<String, String> t = new RedisTemplate<>();
		t.setConnectionFactory(cf);
		t.setKeySerializer(new StringRedisSerializer());
		t.setValueSerializer(new StringRedisSerializer());
		t.setHashKeySerializer(new StringRedisSerializer());
		t.setHashValueSerializer(new StringRedisSerializer());
		return t;
	}
	
}