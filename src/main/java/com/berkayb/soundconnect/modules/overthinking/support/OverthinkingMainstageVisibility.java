package com.berkayb.soundconnect.modules.overthinking.support;

/** Source-level eligibility also applies when an anonymous source is shared by a listener. */
public final class OverthinkingMainstageVisibility {
    private OverthinkingMainstageVisibility() { }

    /** SQL uses alias post. A removed music attachment does not erase retained public text. */
    public static final String SQL = """
            not exists(select 1 from tbl_studio_profile studio where studio.user_id=post.author_id)
            and not exists(select 1 from user_roles author_role join tbl_role role on role.id=author_role.role_id
                where author_role.user_id=post.author_id and role.name='ROLE_STUDIO')
            and not exists(select 1 from tbl_tracks attached join tbl_media_asset media on media.id=attached.media_asset_id
                where (attached.id=post.musician_track_id or attached.id=post.band_track_id)
                  and (media.content_audience<>'MAINSTAGE' or media.owner_type='STUDIO_PROFILE'))
            """;

    public static String sql(String sourceAlias) {
        if (sourceAlias == null || !sourceAlias.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid source alias");
        }
        return SQL.replace("post.", sourceAlias + ".");
    }
}
