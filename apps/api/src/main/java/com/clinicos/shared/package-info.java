/**
 * Cross-cutting technical infrastructure shared by every business module:
 * tenant context, the tenant-scoping transaction listener, and (generated at
 * build time, not checked in) the jOOQ metamodel under {@code shared.jooq}.
 *
 * <p>Declared {@link org.springframework.modulith.ApplicationModule.Type#OPEN
 * OPEN} because it has no business meaning of its own to hide — every
 * module is expected to depend on it.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.clinicos.shared;
