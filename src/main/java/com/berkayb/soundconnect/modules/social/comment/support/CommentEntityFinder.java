package com.berkayb.soundconnect.modules.social.comment.support;

import com.berkayb.soundconnect.modules.social.comment.entity.Comment;
import com.berkayb.soundconnect.modules.social.comment.repository.CommentRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommentEntityFinder {
	
	private final CommentRepository commentRepository;
	
	public Comment getById(UUID commentId) {
		return commentRepository.findById(commentId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
	}
}