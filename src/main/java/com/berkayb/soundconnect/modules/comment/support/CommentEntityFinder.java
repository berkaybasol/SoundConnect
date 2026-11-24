package com.berkayb.soundconnect.modules.comment.support;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommentEntityFinder {
	
	private final CommentRepository commentRepository;
    private final AccountDeliveryFence accounts;

    public void requireVisibleAuthor(UUID viewer, UUID comment, UUID author, boolean writing) {
        if (viewer == null || viewer.equals(author)) return;
        if (writing) accounts.lockInteraction(viewer, author);
        if (commentRepository.hiddenAuthor(viewer, comment)) throw new SoundConnectException(ErrorType.COMMENT_NOT_FOUND);
    }
	
	public Comment getVisibleById(UUID viewer, UUID commentId) {
		return commentRepository.findVisibleComment(viewer, commentId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
	}
}
