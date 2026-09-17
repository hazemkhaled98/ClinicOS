/**
 * Public API of the evaluation module: the monthly request/response records
 * and the lock/override entry points the UI and other business modules are
 * allowed to depend on. Declared as a named interface so Spring Modulith
 * exposes it.
 */
@org.springframework.modulith.NamedInterface("api")
package com.clinicos.evaluation.api;