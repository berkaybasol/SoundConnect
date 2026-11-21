package com.berkayb.soundconnect.shared.recovery;

/** Process-lifetime recovery quarantine. There is deliberately no OPEN/reset API.
 * A selected recovery process never becomes a writer based on restored properties,
 * an enrollment receipt, or a selected-user review result. This is not a global
 * writer fence or a claim that another process has stopped.
 */
public final class RecoveryRuntime {
    private static volatile boolean held;
    private RecoveryRuntime() { }

    static synchronized void quarantine() { held = true; }
    public static boolean isHeld() { return held; }
    public static void requireOperational() {
        if (held) throw new Held();
    }
    public static Runnable guard(Runnable work) {
        requireOperational();
        return () -> { requireOperational(); work.run(); };
    }
    public static final class Held extends IllegalStateException {
        private Held() { super("RECOVERY_HOLD_AUTHORITY_UNPROVEN"); }
    }
}
