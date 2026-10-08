package com.berkayb.soundconnect.modules.admin.health;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** No free-text message, account, installation, device, URL, breadcrumb or token fields. */
public record MobileDiagnosticRequest(@NotNull UUID eventId, @NotNull Severity severity,
        @NotNull Source source, @NotNull ErrorType errorType,
        @NotNull @Size(max = 40) List<@NotNull @Size(max = 240) @Pattern(regexp = FRAME_PATTERN) String> frames,
        @NotNull Environment environment) {
    public static final String FRAME_PATTERN = "^(?:package:soundconnect_23_12_25codx/|dart:)[a-zA-Z0-9_/-]+\\.dart(?::[0-9]{1,7}){1,2}$";
    public enum Severity { ERROR, FATAL }
    public enum Source { FLUTTER_FRAMEWORK, UNHANDLED_ZONE, PLATFORM_DISPATCHER, FRAME_TIMING, DIAGNOSTICS_CHECK, BLOC, RECOVERABLE }
    public enum Environment { local, staging, production }
    public enum ErrorType {
        ApplicationError, StateError, ArgumentError, FormatException, TimeoutException, SocketException,
        HttpException, FirebaseException, DioException, FlutterError, RangeError, TypeError,
        NoSuchMethodError, ConcurrentModificationError, AssertionError, UnsupportedError,
        MissingPluginException, PlatformException, FrameBudgetExceeded, DiagnosticAcceptanceCheck
    }
    @Override public String toString() { return "MobileDiagnosticRequest[redacted]"; }
}
