/**
 * The server-rendered UI: Thymeleaf templates with their controllers, plus
 * drawer/section resolution and the session-state keys read from the
 * {@code identity} module. Depends only on other modules' published APIs —
 * never their {@code internal} packages — enforced by the
 * {@code allowedDependencies} whitelist below and verified by
 * {@code ModularityTests}. As business modules gain real API packages in later
 * phases, add them here.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared",
                "identity :: api", "clinicconfig :: api", "staff :: api", "evaluation :: api",
                "academy", "prep", "inventory", "procedures"
        })
package com.clinicos.ui;
