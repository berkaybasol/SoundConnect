package com.berkayb.soundconnect.modules.feed.listener.core;

/** Constant SQL fragments used before candidate limits and on immutable replay checks. */
public final class ListenerFeedSourceSql {
    private ListenerFeedSourceSql() { }

    public static String overthinking(String sourceAlias) {
        return "(" + com.berkayb.soundconnect.modules.overthinking.support.OverthinkingMainstageVisibility.sql(sourceAlias) + ")";
    }
}
