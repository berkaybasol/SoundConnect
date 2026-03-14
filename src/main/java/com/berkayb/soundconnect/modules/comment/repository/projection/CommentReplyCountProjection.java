package com.berkayb.soundconnect.modules.comment.repository.projection;

import java.util.UUID;

public interface CommentReplyCountProjection {
	
	UUID getParentCommentId();
	
	long getReplyCount();
}