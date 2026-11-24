package com.berkayb.soundconnect.modules.contentreport;

public enum ContentReportKind {
    LISTENER_PROFILE("LISTENER"), MUSICIAN_PROFILE("MUSICIAN"), VENUE_PROFILE("VENUE"), STUDIO_PROFILE("STUDIO"),
    OVERTHINKING_POST, COMMENT, EVENT_PROFILE_SHARE, OVERTHINKING_PROFILE_SHARE, TABLEGROUP_PROFILE_SHARE;

    final String profileType;

    ContentReportKind() { this(null); }
    ContentReportKind(String profileType) { this.profileType = profileType; }

    boolean isProfile() { return profileType != null; }
}
