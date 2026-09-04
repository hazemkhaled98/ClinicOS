/**
 * The Vaadin Flow UI. Depends only on other modules' published APIs, never
 * their {@code internal} packages — enforced by the {@code allowedDependencies}
 * whitelist below and verified by {@code ModularityTests}. As business
 * modules gain real API packages in later phases, add them here.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared",
                "identity", "clinicconfig", "staff", "evaluation",
                "academy", "prep", "inventory", "procedures"
        })
package com.clinicos.ui;
