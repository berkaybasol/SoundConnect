package com.berkayb.soundconnect.modules.overthinking.support;

public final class OverthinkingBlockVisibility {
    private OverthinkingBlockVisibility() { }

    /** A block cannot change an anonymous source's visibility even after a prior reveal approval. */
    public static final String VISIBLE_SOURCE = """
            (not exists (select 1 from tbl_user_block b where b.unblocked_at is null
                and ((b.blocker_id=:viewer and b.blocked_id=post.author_id)
                  or (b.blocked_id=:viewer and b.blocker_id=post.author_id)))
              or post.visibility_type='ANONYMOUS')
            """;
}
