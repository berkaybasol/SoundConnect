package com.berkayb.soundconnect.modules.contentreport;

/** Immutable, server-selected visible fields. Author identifiers are deliberately absent. */
public record ContentReportEvidence(String targetLabel, String evidenceText, String parentText) { }
