package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.ActivityLog.ACTIVITY_LOG;
import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.ClinicProfileService;
import com.clinicos.identity.api.ClinicProfileService.ClinicIdentity;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class ClinicProfileServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ClinicProfileService clinicProfileService;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID clinicId;
    private String slug;
    private UUID ownerMembership;
    private UUID managerMembership;
    private UUID otherClinicId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            slug = "old-code-" + UUID.randomUUID().toString().substring(0, 8);
            clinicId = TestFixtures.insertClinic(connection, "عيادة قديمة", slug);
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            TestFixtures.insertUser(connection, clinicId, "dr-sara", "sara-pass", "active", "د. سارة");
            ownerMembership = TestFixtures.insertMembership(connection, clinicId, "owner");
            managerMembership = TestFixtures.insertMembership(connection, clinicId, "manager");
            otherClinicId = TestFixtures.insertClinic(connection, "عيادة أخرى", "other-" + slug);
        }
        TenantContext.set(clinicId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void currentReturnsTheStoredNameAndSlug() {
        assertThat(clinicProfileService.current(clinicId))
                .isEqualTo(new ClinicIdentity("عيادة قديمة", slug));
    }

    @Test
    void ownerRenamesTheClinicAndChangesTheLoginSlug() {
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug())).isEmpty();

        assertThat(clinicProfileService.current(clinicId))
                .isEqualTo(new ClinicIdentity("عيادة النور", newSlug()));
    }

    @Test
    void theSlugIsNormalizedToLowercaseBeforeItIsStored() {
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", "  " + newSlug().toUpperCase() + "  "))
                .isEmpty();

        assertThat(clinicProfileService.current(clinicId).slug()).isEqualTo(newSlug());
    }

    @Test
    void aRenameAloneNeverReDerivesTheSlug() {
        clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug());

        clinicProfileService.update(clinicId, ownerMembership, "عيادة النور الجديدة", newSlug());

        assertThat(clinicProfileService.current(clinicId))
                .isEqualTo(new ClinicIdentity("عيادة النور الجديدة", newSlug()));
    }

    @Test
    void aClinicCreatedWithALegacyShortCodeCanChangeItsName() throws Exception {
        UUID legacyClinic;
        UUID legacyOwner;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            legacyClinic = TestFixtures.insertClinic(connection, "عيادة قصيرة", "x");
            legacyOwner = TestFixtures.insertMembership(connection, legacyClinic, "owner");
        }
        TenantContext.set(legacyClinic);

        assertThat(clinicProfileService.update(legacyClinic, legacyOwner, "عيادة جديدة", "x")).isEmpty();
        assertThat(clinicProfileService.current(legacyClinic))
                .isEqualTo(new ClinicIdentity("عيادة جديدة", "x"));
    }

    @Test
    void theNewSlugLogsMembersInAndTheOldOneNoLongerResolves() {
        String password = "correct-horse-battery-staple";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID userId = TestFixtures.insertUser(connection, clinicId, "renamed-" + slug,
                    passwordEncoder.encode(password), "active");
            TestFixtures.insertMembership(connection, clinicId, userId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug());

        assertThat(fetchCredentialCount(newSlug())).isEqualTo(1L);
        assertThat(fetchCredentialCount(slug)).isZero();
        assertThat(authenticationManager.authenticate(login(newSlug(), "renamed-" + slug, password))
                .isAuthenticated()).isTrue();
        assertThatThrownBy(() -> authenticationManager.authenticate(login(slug, "renamed-" + slug, password)))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static UsernamePasswordAuthenticationToken login(String clinicCode, String username, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("clinic", clinicCode);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, password);
        token.setDetails(new ClinicWebAuthenticationDetails(request));
        return token;
    }

    private String newSlug() {
        return "bright-smile-" + slug.substring("old-code-".length());
    }

    private long fetchCredentialCount(String slug) {
        return transactionTemplate.execute(status -> ((Number) dsl.fetchValue(
                "select count(*) from app_user_credentials_lookup_by_clinic_username(?::text, ?::citext)",
                slug, "dr-sara")).longValue());
    }

    @Test
    void aManagerCannotRenameTheClinic() {
        assertThatThrownBy(() -> clinicProfileService.update(clinicId, managerMembership, "اختطاف", "hijack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("تعديل بيانات العيادة متاح لمالك العيادة فقط");

        assertThat(clinicProfileService.current(clinicId).slug()).isEqualTo(slug);
    }

    @Test
    void anotherTenantCannotBeEdited() {
        assertThatThrownBy(() -> clinicProfileService.update(otherClinicId, ownerMembership, "اختراق", "breach"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("لا يمكن تعديل بيانات عيادة أخرى");
    }

    @Test
    void aSlugAlreadyTakenByAnotherClinicIsAFieldError() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            TestFixtures.insertClinic(connection, "عيادة منافسة", newSlug());
        }

        assertThat(clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug()))
                .containsEntry("slug", "كود العيادة مستخدم بالفعل، اختر كودًا آخر");

        assertThat(clinicProfileService.current(clinicId)).isEqualTo(new ClinicIdentity("عيادة قديمة", slug));
        assertThat(identityAuditCount()).isZero();
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
    }

    @Test
    void aBlankNameOrMalformedSlugIsRejectedAsAFieldError() {
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "   ", "ok-code"))
                .containsKey("name");
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "اسم", "a"))
                .containsKey("slug");
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "اسم", "-leading-dash"))
                .containsKey("slug");
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "اسم", "a".repeat(41)))
                .containsKey("slug");

        assertThat(clinicProfileService.current(clinicId))
                .isEqualTo(new ClinicIdentity("عيادة قديمة", slug));
    }

    @Test
    void aManagerRejectionWritesNoAuditRowAndNoNotification() {
        assertThatThrownBy(() -> clinicProfileService.update(clinicId, managerMembership, "اختطاف", "hijack"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(identityAuditCount()).isZero();
    }

    @Test
    void renamingTheSameValuesIsANoopThatWritesNothing() {
        assertThat(clinicProfileService.update(clinicId, ownerMembership, "عيادة قديمة", slug)).isEmpty();

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(identityAuditCount()).isZero();
    }

    @Test
    void aRealChangeAuditsTheOldAndNewValuesAndNotifiesEveryActiveMember() {
        clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug());

        var audit = transactionTemplate.execute(status -> dsl.select(ACTIVITY_LOG.ACTION, ACTIVITY_LOG.ENTITY_TYPE,
                        ACTIVITY_LOG.DETAIL)
                .from(ACTIVITY_LOG)
                .where(ACTIVITY_LOG.ACTOR_MEMBERSHIP_ID.eq(ownerMembership))
                .fetchOne());
        assertThat(audit).isNotNull();
        assertThat(audit.get(ACTIVITY_LOG.ACTION)).isEqualTo("clinic.identity_changed");
        assertThat(audit.get(ACTIVITY_LOG.ENTITY_TYPE)).isEqualTo("clinic");
        assertThat(audit.get(ACTIVITY_LOG.DETAIL).data())
                .contains("oldName", "عيادة قديمة", "newName", "عيادة النور")
                .contains("oldSlug", slug, "newSlug", newSlug());

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.recent(clinicId, managerMembership, 20).get(0).kind())
                .isEqualTo(NotificationKind.CLINIC_IDENTITY_CHANGED);
        assertThat(notifications.recent(clinicId, managerMembership, 20).get(0).payload())
                .containsEntry("name", "عيادة النور")
                .containsEntry("slug", newSlug());
    }

    @Test
    void concurrentEditAuditsTheIdentityThatWasActuallyReplaced() throws Exception {
        try (Connection blocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Connection observer = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement update = blocker.prepareStatement("update clinic set name = ? where id = ?")) {
                update.setString(1, "عيادة وسطية");
                update.setObject(2, clinicId);
                update.executeUpdate();
            }
            var change = executor.submit(() -> {
                TenantContext.set(clinicId);
                try {
                    return clinicProfileService.update(clinicId, ownerMembership, "عيادة النور", newSlug());
                } finally {
                    TenantContext.clear();
                }
            });
            try {
                boolean waiting = false;
                for (int i = 0; i < 250 && !waiting; i++) {
                    if (change.isDone()) {
                        change.get(1, TimeUnit.SECONDS);
                        throw new AssertionError("Identity edit finished before blocked on clinic row");
                    }
                    try (PreparedStatement query = observer.prepareStatement(
                            "select exists(select 1 from pg_stat_activity where wait_event_type = 'Lock' "
                                    + "and query like '%clinic%' and pid <> pg_backend_pid())")) {
                        try (ResultSet result = query.executeQuery()) {
                            result.next();
                            waiting = result.getBoolean(1);
                        }
                    }
                    if (!waiting) {
                        Thread.sleep(20);
                    }
                }
                assertThat(waiting).isTrue();
            } finally {
                blocker.commit();
            }
            assertThat(change.get(10, TimeUnit.SECONDS)).isEmpty();
            String detail = transactionTemplate.execute(status -> dsl.select(ACTIVITY_LOG.DETAIL)
                    .from(ACTIVITY_LOG).where(ACTIVITY_LOG.CLINIC_ID.eq(clinicId))
                    .and(ACTIVITY_LOG.ACTION.eq("clinic.identity_changed"))
                    .fetchOne(ACTIVITY_LOG.DETAIL).data());
            assertThat(detail).contains("\"oldName\": \"عيادة وسطية\"");
        }
    }

    @Test
    void failedAuditAbortsTheIdentityChange() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = admin.createStatement()) {
            statement.execute("create function reject_identity_audit() returns trigger language plpgsql as $$ "
                    + "begin raise exception 'audit unavailable'; end $$");
            statement.execute("create trigger reject_identity_audit before insert on activity_log "
                    + "for each row when (new.action = 'clinic.identity_changed') "
                    + "execute function reject_identity_audit()");
            try {
                assertThatThrownBy(() -> clinicProfileService.update(clinicId, ownerMembership,
                        "عيادة النور", newSlug()))
                        .hasMessageContaining("audit unavailable");
                assertThat(clinicProfileService.current(clinicId))
                        .isEqualTo(new ClinicIdentity("عيادة قديمة", slug));
                assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
            } finally {
                statement.execute("drop trigger reject_identity_audit on activity_log");
                statement.execute("drop function reject_identity_audit()");
            }
        }
    }

    private int identityAuditCount() {
        return transactionTemplate.execute(status -> dsl.fetchCount(ACTIVITY_LOG,
                ACTIVITY_LOG.CLINIC_ID.eq(clinicId)
                        .and(ACTIVITY_LOG.ACTION.eq("clinic.identity_changed"))));
    }

}
