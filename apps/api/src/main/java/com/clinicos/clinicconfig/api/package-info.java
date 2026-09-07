/**
 * Public API of the clinicconfig module: the clinic-wide settings aggregate
 * (shift defaults, grace, evaluation weights, incentive tiers) that the UI is
 * allowed to depend on. Declared as a named interface so Spring Modulith
 * exposes it.
 */
@org.springframework.modulith.NamedInterface("api")
package com.clinicos.clinicconfig.api;