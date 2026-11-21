package com.berkayb.soundconnect.shared.telemetry.tracing;

import java.util.regex.Pattern;

/** No Throwable, cause message or arbitrary stack text is retained by telemetry. */
record SafeExceptionMetadata(String type, String file, long line) {
    private static final Pattern TYPE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$]{0,159}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,95}\\.java");

    static SafeExceptionMetadata from(Throwable error) {
        String type = error.getClass().getName();
        if (!TYPE.matcher(type).matches()) type = "java.lang.Exception";
        // Bound cause traversal (including cycles) and inspected frames.
        for (int cause = 0; error != null && cause < 8; cause++, error = error.getCause()) {
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 64); i++) {
                StackTraceElement frame = frames[i];
                if (frame.getClassName().startsWith("com.berkayb.soundconnect.")
                        && frame.getFileName() != null && FILE.matcher(frame.getFileName()).matches()
                        && frame.getLineNumber() > 0 && frame.getLineNumber() < 1_000_000) {
                    return new SafeExceptionMetadata(type, frame.getFileName(), frame.getLineNumber());
                }
            }
        }
        return new SafeExceptionMetadata(type, "UNKNOWN", 0);
    }
}
