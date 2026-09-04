package com.clinicos;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Proves the module boundaries declared across {@code package-info.java}
 * files actually hold -- in particular that {@code ui} never reaches into
 * another module's {@code internal} package. Run on every phase (see
 * docs/roadmap.md's Verification section).
 */
class ModularityTests {

    @Test
    void moduleStructureIsRespected() {
        ApplicationModules.of(Application.class).verify();
    }
}
