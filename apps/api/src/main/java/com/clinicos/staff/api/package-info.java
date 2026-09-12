/**
 * Public API of the staff module: the employee aggregate (roster, role,
 * compensation, shifts) that the UI and other business modules are allowed to
 * depend on. Declared as a named interface so Spring Modulith exposes it.
 */
@org.springframework.modulith.NamedInterface("api")
package com.clinicos.staff.api;