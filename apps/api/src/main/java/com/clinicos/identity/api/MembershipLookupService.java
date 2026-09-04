package com.clinicos.identity.api;

import java.util.List;
import java.util.UUID;

public interface MembershipLookupService {

    List<Membership> findByUserId(UUID userId);

    record Membership(UUID membershipId, UUID clinicId, String clinicName, String roleCode) {
    }
}
