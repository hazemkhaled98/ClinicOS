package com.clinicos.identity.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface ClinicProfileService {

    ClinicIdentity current(UUID clinicId);

    Map<String, String> update(UUID clinicId, UUID actorMembershipId, String name, String slug);

    record ClinicIdentity(String name, String slug) {
        public ClinicIdentity {
            name = Objects.requireNonNull(name, "name");
            slug = Objects.requireNonNull(slug, "slug");
        }
    }
}
