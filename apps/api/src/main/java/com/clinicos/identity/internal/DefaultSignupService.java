package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.SignupClinicWithOwner.SIGNUP_CLINIC_WITH_OWNER;

import java.util.Locale;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.SignupService;
import com.clinicos.shared.TenantContext;

/**
 * Self-service clinic sign-up (UC-001, Phase 1b). The only mutation that runs
 * before any tenant exists, so it goes through the V13
 * {@code signup_clinic_with_owner} {@code SECURITY DEFINER} function under the
 * {@code TenantContext} auth-mode escape, in one transaction.
 *
 * <p>The slug is derived in Java, not SQL: lowercased, non-alphanumerics
 * collapsed to single dashes, trimmed. An Arabic clinic name reduces to an
 * empty slug, which falls back to a short random suffix; a first slug collision
 * is retried once with a random suffix before the conflict surfaces.
 *
 * <p>This service is the soft point of a public (pre-auth) surface: there is
 * deliberately no CAPTCHA, no rate limiting, and no email verification here.
 * Those must be added before the app is publicly reachable (Phase 9 hardening)
 * — ponytail: that ceiling exists on purpose; do not remove it without adding
 * email verification and signup throttling first.
 */
@Service
public class DefaultSignupService implements SignupService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;

    public DefaultSignupService(DSLContext dsl, TransactionTemplate transactionTemplate,
            PasswordEncoder passwordEncoder) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public SignupResult signUp(SignupRequest request) {
        String slug = deriveSlug(request.clinicName());
        if (slug.isEmpty()) {
            slug = "clinic-" + randomSuffix();
        }
        String passwordHash = passwordEncoder.encode(request.rawPassword());

        TenantContext.enterAuthMode();
        try {
            try {
                return attempt(slug, request, passwordHash);
            } catch (DuplicateKeyException e) {
                if (isSlugConflict(e)) {
                    return attempt(slug + "-" + randomSuffix(), request, passwordHash);
                }
                throw mapConflict(e);
            }
        } catch (DuplicateKeyException e) {
            throw mapConflict(e);
        } finally {
            TenantContext.exitAuthMode();
        }
    }

    private SignupResult attempt(String slug, SignupRequest request, String passwordHash) {
        return transactionTemplate.execute(status -> {
            var record = dsl.selectFrom(SIGNUP_CLINIC_WITH_OWNER.call(
                    DSL.val(request.clinicName()), DSL.val(slug), DSL.val(request.fullName()),
                    CredentialsLookupService.citext(request.username()),
                    CredentialsLookupService.citext(request.email()), DSL.val(passwordHash)))
                    .fetchOne();
            if (record == null) {
                throw new IllegalStateException("signup_clinic_with_owner returned no row");
            }
            return new SignupResult(record.getUserId(), record.getClinicId(), record.getMembershipId());
        });
    }

    private SignupConflictException mapConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        if (message.contains("app_user_username_key")) {
            return new SignupConflictException(SignupConflictException.Field.USERNAME, message);
        }
        if (message.contains("idx_app_user_email_when_not_null")) {
            return new SignupConflictException(SignupConflictException.Field.EMAIL, message);
        }
        if (message.contains("clinic_slug_key")) {
            return new SignupConflictException(SignupConflictException.Field.CLINIC_SLUG, message);
        }
        throw e;
    }

    private boolean isSlugConflict(DuplicateKeyException e) {
        return e.getMostSpecificCause().getMessage().contains("clinic_slug_key");
    }

    /**
     * Lowercases and normalizes a clinic name to a URL-safe slug:
     * non-alphanumerics become single dashes, leading/trailing dashes are
     * trimmed. Arabic-only names reduce to the empty string.
     */
    static String deriveSlug(String clinicName) {
        return clinicName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    static String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}