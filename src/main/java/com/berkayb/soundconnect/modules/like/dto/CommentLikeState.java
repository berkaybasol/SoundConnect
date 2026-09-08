package com.berkayb.soundconnect.modules.like.dto;

/** Desired-state writes and explicit reconciliation return the same authoritative snapshot. */
public record CommentLikeState(long likeCount,boolean likedByMe) { }
