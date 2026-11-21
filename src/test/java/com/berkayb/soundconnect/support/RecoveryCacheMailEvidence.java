package com.berkayb.soundconnect.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Safe, exclusive evidence output; no credentials or mail payloads. */
public final class RecoveryCacheMailEvidence {
    private RecoveryCacheMailEvidence() { }

    public static void write(String scenario, Map<String, Object> observations) throws Exception {
        var record = new LinkedHashMap<String, Object>(observations);
        record.put("observedAtUtc", Instant.now().toString());
        record.put("scenario", scenario);
        record.put("externalProviderCalled", false);
        record.put("productionRecoveryAccepted", false);
        String configured = System.getProperty("sc.recovery.cacheMailEvidenceDir");
        if (configured != null) {
            Path directory = Path.of(configured);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(scenario + ".json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(record),
                    StandardOpenOption.CREATE_NEW);
        }
        System.out.println("CACHE_MAIL_ACCEPTANCE " + new ObjectMapper().writeValueAsString(record));
    }
}
