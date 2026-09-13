package com.berkayb.soundconnect.modules.feed.musician.moderation;

import java.util.Arrays;
import java.util.stream.Collectors;

/** SQL expressions come exclusively from provider source code, never request data. */
public final class MusicianFeedRestrictionSql {
    private MusicianFeedRestrictionSql() { }

    public static String item(String itemId) { return "'ITEM:' || (" + itemId + ")"; }
    public static String target(String type, String id) {
        return "'TARGET:' || (" + type + ") || ':' || (" + id + ")::text";
    }
    public static String profile(String type, String id) {
        return "'TARGET:PROFILE:' || (" + type + ") || ':' || (" + id + ")::text";
    }

    public static String allowed(String... scopeExpressions) {
        if (scopeExpressions.length == 0) throw new IllegalArgumentException("Restriction scopes are required");
        return """
                not exists (
                    select 1 from (values %s) moderation_scope(scope_key)
                    join lateral (
                        select 1 from tbl_musician_feed_restriction restriction
                        where restriction.scope_key=moderation_scope.scope_key and restriction.active
                        order by restriction.report_id limit 1
                    ) blocked on true
                )
                """.formatted(Arrays.stream(scopeExpressions).map(value -> "(" + value + ")")
                .collect(Collectors.joining(",")));
    }
}
