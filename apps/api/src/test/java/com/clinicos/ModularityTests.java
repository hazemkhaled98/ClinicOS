package com.clinicos;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void moduleStructureIsRespected() {
        ApplicationModules.of(Application.class).verify();
    }
}
