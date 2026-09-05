package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUserMembershipsLookup.APP_USER_MEMBERSHIPS_LOOKUP;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.MembershipLookupService;

@Service
public class DefaultMembershipLookupService implements MembershipLookupService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultMembershipLookupService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Membership> findByUserId(UUID userId) {
        return transactionTemplate.execute(status -> dsl.selectFrom(APP_USER_MEMBERSHIPS_LOOKUP.call(userId))
                .fetch(r -> new Membership(r.getMembershipId(), r.getClinicId(), r.getClinicName(), r.getRoleCode())));
    }
}