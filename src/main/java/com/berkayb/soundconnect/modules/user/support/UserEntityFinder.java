package com.berkayb.soundconnect.modules.user.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserEntityFinder {
	
	private final UserRepository userRepository;
	
	public User getUser(UUID id) {
		return userRepository.findById(id)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
	}
	
	
	public User getUserByUsername(String username) {
		return userRepository.findByUsername(username)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
	}
}