package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.Notification.NOTIFICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.shared.NotificationService.Notification;
import com.clinicos.shared.jooq.enums.MembershipStatus;

@SpringBootTest(classes = Application.class)
class NotificationServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notifications;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DSLContext dsl;

    private UUID clinicId;
    private UUID ownerMembership;
    private UUID managerMembership;
    private UUID assistantMembership;
    private UUID assistantEmployeeId;
    private UUID unlinkedEmployeeId;
    private UUID suspendedManagerMembership;
    private UUID suspendedAssistantMembership;
    private UUID suspendedAssistantEmployeeId;
    private UUID otherClinicId;
    private UUID otherClinicMembership;

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicId = TestFixtures.insertClinic(connection);
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            ownerMembership = TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId), "owner");
            managerMembership = TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId), "manager");

            assistantEmployeeId = TestFixtures.insertEmployee(connection, clinicId, "مساعد");
            assistantMembership = TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId), "assistant");
            TestFixtures.linkMembershipToEmployee(connection, assistantMembership, assistantEmployeeId);

            unlinkedEmployeeId = TestFixtures.insertEmployee(connection, clinicId, "بلا حساب");

            suspendedManagerMembership = TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId), "manager", MembershipStatus.suspended);

            suspendedAssistantEmployeeId = TestFixtures.insertEmployee(connection, clinicId, "موقوف");
            suspendedAssistantMembership = TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId), "assistant", MembershipStatus.suspended);
            TestFixtures.linkMembershipToEmployee(connection, suspendedAssistantMembership,
                    suspendedAssistantEmployeeId);

            otherClinicId = TestFixtures.insertClinic(connection);
            otherClinicMembership = TestFixtures.insertMembership(connection, otherClinicId,
                    TestFixtures.insertUser(connection, otherClinicId), "owner");
        }
        TenantContext.set(clinicId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void unreadCountAndRecentAreScopedToTheRecipient() {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        notifications.notifyMembership(clinicId, ownerMembership, managerMembership,
                NotificationKind.ACADEMY_SUBMISSION_VERIFIED, Map.of("unit", "تعقيم"));
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.TASK_ASSIGNMENT_REJECTED,
                Map.of("task", "تعقيم", "reason", "غير كافٍ"));

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isEqualTo(2);
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, assistantMembership)).isZero();

        List<Notification> ownerRecent = notifications.recent(clinicId, ownerMembership, 20);
        assertThat(ownerRecent).hasSize(2);
        assertThat(ownerRecent.get(0).kind()).isEqualTo(NotificationKind.TASK_ASSIGNMENT_REJECTED);
        assertThat(ownerRecent.get(0).payload()).containsEntry("reason", "غير كافٍ");
        assertThat(ownerRecent.get(0).read()).isFalse();
        assertThat(ownerRecent.get(1).kind()).isEqualTo(NotificationKind.DAILY_TASK_APPROVED);
    }

    @Test
    void recentClampsTheLimitAtBothEnds() {
        for (int i = 0; i < 3; i++) {
            notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                    Map.of("task", "تعقيم"));
        }

        assertThat(notifications.recent(clinicId, ownerMembership, 0)).hasSize(1);
        assertThat(notifications.recent(clinicId, ownerMembership, -5)).hasSize(1);
        assertThat(notifications.recent(clinicId, ownerMembership, 99_999)).hasSize(3);
    }

    @Test
    void markReadTouchesOnlyTheCallingRecipientsNotification() {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        notifications.notifyMembership(clinicId, ownerMembership, managerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        UUID ownerId = notifications.recent(clinicId, ownerMembership, 20).get(0).id();

        Notification read = notifications.markRead(clinicId, ownerMembership, ownerId);

        assertThat(read.read()).isTrue();
        assertThat(read.readAt()).isNotNull();
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
    }

    @Test
    void markReadRejectsAnotherRecipientsNotification() {
        notifications.notifyMembership(clinicId, ownerMembership, managerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        UUID managerId = notifications.recent(clinicId, managerMembership, 20).get(0).id();

        assertThatThrownBy(() -> notifications.markRead(clinicId, ownerMembership, managerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("الإشعار غير موجود");
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
    }

    @Test
    void markReadReturnsAnAlreadyReadNotification() {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        UUID id = notifications.recent(clinicId, ownerMembership, 20).get(0).id();
        notifications.markRead(clinicId, ownerMembership, id);

        assertThat(notifications.markRead(clinicId, ownerMembership, id).read()).isTrue();
    }

    @Test
    void markAllReadClearsOnlyTheCallingRecipient() {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.TASK_ASSIGNMENT_APPROVED,
                Map.of("task", "تعقيم"));
        notifications.notifyMembership(clinicId, ownerMembership, managerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));

        assertThat(notifications.markAllRead(clinicId, ownerMembership)).isEqualTo(2);

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.recent(clinicId, ownerMembership, 20)).allMatch(Notification::read);
    }

    @Test
    void notifyEmployeeReachesTheLinkedActiveMembershipAndNobodyElse() {
        int created = notifications.notifyEmployee(clinicId, ownerMembership, assistantEmployeeId,
                NotificationKind.ACADEMY_SUBMISSION_VERIFIED, Map.of("unit", "تعقيم الأدوات"));

        assertThat(created).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, assistantMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(notifications.recent(clinicId, assistantMembership, 20).get(0).payload())
                .containsEntry("unit", "تعقيم الأدوات");
    }

    @Test
    void notifyEmployeeIsANoopWhenNoMembershipIsLinked() {
        assertThat(notifications.notifyEmployee(clinicId, ownerMembership, unlinkedEmployeeId,
                NotificationKind.ACADEMY_SUBMISSION_REJECTED,
                Map.of("unit", "تعقيم", "reason", "ناقصة"))).isZero();
    }

    @Test
    void notifyEmployeeSkipsASuspendedMembership() {
        assertThat(notifications.notifyEmployee(clinicId, ownerMembership, suspendedAssistantEmployeeId,
                NotificationKind.ACADEMY_SUBMISSION_VERIFIED, Map.of("unit", "تعقيم"))).isZero();
    }

    @Test
    void notifyRolesFansOutToActiveOwnersAndManagersButNeverTheActor() {
        int created = notifications.notifyRoles(clinicId, ownerMembership, Set.of("owner", "manager"),
                NotificationKind.INVENTORY_CHANGE_REQUESTED, Map.of("item", "قفازات", "changeKind", "edit"));

        assertThat(created).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, assistantMembership)).isZero();
        assertThat(notifications.unreadCount(clinicId, suspendedManagerMembership)).isZero();
    }

    @Test
    void notifyApproversReachesOwnerAndPermittedManagerButNotTheActorOrStaff() {
        int created = notifications.notifyApprovers(clinicId, assistantMembership, "acadVerify",
                NotificationKind.ACADEMY_PHOTO_SUBMITTED, Map.of("unit", "تعقيم الأدوات"));

        assertThat(created).isEqualTo(2);
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, assistantMembership)).isZero();
        assertThat(notifications.unreadCount(clinicId, suspendedManagerMembership)).isZero();
    }

    @Test
    void notifyApproversSkipsManagersWhoLackThePermission() {
        int created = notifications.notifyApprovers(clinicId, assistantMembership, "gatedOnly",
                NotificationKind.PROCEDURE_CHANGE_REQUESTED, Map.of("procedure", "حقن"));

        assertThat(created).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isZero();
    }

    @Test
    void notifyApproversWithoutAGateReachesEveryManager() {
        int created = notifications.notifyApprovers(clinicId, assistantMembership, null,
                NotificationKind.PREP_CHECKLIST_REQUESTED, Map.of("checklist", "قائمة البداية"));

        assertThat(created).isEqualTo(2);
        assertThat(notifications.unreadCount(clinicId, managerMembership)).isEqualTo(1);
        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isEqualTo(1);
    }

    @Test
    void notifyApproversRejectsAnIncompletePayload() {
        assertThatThrownBy(() -> notifications.notifyApprovers(clinicId, assistantMembership, "acadVerify",
                NotificationKind.ACADEMY_PHOTO_SUBMITTED, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("بيانات الإشعار غير مكتملة");
    }

    @Test
    void notifyRolesRejectsAnEmptyRoleSet() {
        assertThatThrownBy(() -> notifications.notifyRoles(clinicId, ownerMembership, Set.of(),
                NotificationKind.SUPPLIER_RETURN_REQUESTED, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesRejectPayloadsMissingKindRequiredKeys() {
        invalidPayloads().forEach((kind, payload) -> assertThatThrownBy(
                () -> notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, kind, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("بيانات الإشعار غير مكتملة"));
    }

    @Test
    void inventoryChangeRequiresAnActionWhenWriting() {
        assertThatThrownBy(() -> notifications.notifyMembership(clinicId, ownerMembership, ownerMembership,
                NotificationKind.INVENTORY_CHANGE_REQUESTED,
                Map.of("item", "قفازات", "changeKind", "unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("بيانات الإشعار غير مكتملة");
    }

    @Test
    void notifyMembershipSkipsAnInactiveOrForeignRecipient() {
        assertThat(notifications.notifyMembership(clinicId, ownerMembership, suspendedAssistantMembership,
                NotificationKind.DAILY_TASK_APPROVED, Map.of("task", "تعقيم"))).isZero();
        assertThat(notifications.notifyMembership(clinicId, ownerMembership, otherClinicMembership,
                NotificationKind.DAILY_TASK_APPROVED, Map.of("task", "تعقيم"))).isZero();
    }

    @Test
    void readsNeverLeakAnotherTenantsRows() {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));

        assertThat(notifications.unreadCount(clinicId, otherClinicMembership)).isZero();
        assertThat(notifications.recent(clinicId, otherClinicMembership, 20)).isEmpty();
    }

    @Test
    void clinicArgumentMustMatchTheBoundTenant() {
        assertThatThrownBy(() -> notifications.unreadCount(otherClinicId, otherClinicMembership))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound matching clinic");
    }

    @Test
    void everyCallFailsLoudlyWithoutATenantBound() {
        TenantContext.clear();

        assertThatThrownBy(() -> notifications.unreadCount(clinicId, ownerMembership))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notifications.notifyMembership(clinicId, ownerMembership, ownerMembership,
                NotificationKind.DAILY_TASK_APPROVED, Map.of("task", "تعقيم")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailingCallerTransactionRollsTheNotificationBack() {
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                    Map.of("task", "تعقيم"));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(notifications.unreadCount(clinicId, ownerMembership)).isZero();
    }

    @Test
    void rowsCarryTheEnumLiteralAJsonPayloadAndNoReadStamp() throws Exception {
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.ACADEMY_SUBMISSION_REJECTED,
                Map.of("unit", "تعقيم", "reason", "صورة غير واضحة"));
        TenantContext.clear();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            var row = dsl.select(NOTIFICATION.KIND, NOTIFICATION.PAYLOAD, NOTIFICATION.READ_AT)
                    .from(NOTIFICATION)
                    .where(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(ownerMembership))
                    .fetchOne();
            assertThat(row.get(NOTIFICATION.KIND)).isEqualTo("ACADEMY_SUBMISSION_REJECTED");
            assertThat(row.get(NOTIFICATION.PAYLOAD).data()).contains("\"reason\"");
            assertThat(row.get(NOTIFICATION.READ_AT)).isNull();
        }
    }

    @Test
    void recentRejectsAnUnknownPersistedKind() {
        transactionTemplate.executeWithoutResult(status -> dsl.insertInto(NOTIFICATION)
                .set(NOTIFICATION.CLINIC_ID, clinicId)
                .set(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID, ownerMembership)
                .set(NOTIFICATION.KIND, "UNKNOWN")
                .set(NOTIFICATION.PAYLOAD, JSONB.valueOf("{}"))
                .execute());

        assertThatThrownBy(() -> notifications.recent(clinicId, ownerMembership, 1))
                .isInstanceOf(MalformedNotificationDataException.class)
                .hasMessage("نوع إشعار غير مدعوم");
    }

    @Test
    void recentRejectsAPayloadThatIsNotAnObject() {
        transactionTemplate.executeWithoutResult(status -> dsl.insertInto(NOTIFICATION)
                .set(NOTIFICATION.CLINIC_ID, clinicId)
                .set(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID, ownerMembership)
                .set(NOTIFICATION.KIND, NotificationKind.DAILY_TASK_APPROVED.literal())
                .set(NOTIFICATION.PAYLOAD, JSONB.valueOf("[]"))
                .execute());

        assertThatThrownBy(() -> notifications.recent(clinicId, ownerMembership, 1))
                .isInstanceOf(MalformedNotificationDataException.class)
                .hasMessage("تعذر عرض بيانات الإشعار");
    }

    @Test
    void recentRejectsANullPayload() {
        transactionTemplate.executeWithoutResult(status -> dsl.insertInto(NOTIFICATION)
                .set(NOTIFICATION.CLINIC_ID, clinicId)
                .set(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID, ownerMembership)
                .set(NOTIFICATION.KIND, NotificationKind.DAILY_TASK_APPROVED.literal())
                .set(NOTIFICATION.PAYLOAD, JSONB.valueOf("null"))
                .execute());

        assertThatThrownBy(() -> notifications.recent(clinicId, ownerMembership, 1))
                .isInstanceOf(MalformedNotificationDataException.class)
                .hasMessage("تعذر عرض بيانات الإشعار");
    }

    @Test
    void recentRejectsPayloadsMissingKindRequiredKeys() {
        invalidPayloadJsons().forEach((kind, payload) -> {
            transactionTemplate.executeWithoutResult(status -> dsl.insertInto(NOTIFICATION)
                    .set(NOTIFICATION.CLINIC_ID, clinicId)
                    .set(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID, ownerMembership)
                    .set(NOTIFICATION.KIND, kind.literal())
                    .set(NOTIFICATION.PAYLOAD, JSONB.valueOf(payload))
                    .execute());

            assertThatThrownBy(() -> notifications.recent(clinicId, ownerMembership, 1))
                    .isInstanceOf(MalformedNotificationDataException.class)
                    .hasMessage("بيانات الإشعار غير مكتملة");

            transactionTemplate.executeWithoutResult(status -> dsl.deleteFrom(NOTIFICATION).execute());
        });
    }

    @Test
    void createdAtIsServerDefaulted() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        notifications.notifyMembership(clinicId, ownerMembership, ownerMembership, NotificationKind.DAILY_TASK_APPROVED,
                Map.of("task", "تعقيم"));

        Notification n = notifications.recent(clinicId, ownerMembership, 1).get(0);

        assertThat(n.createdAt()).isAfterOrEqualTo(before);
    }

    private static Map<NotificationKind, Map<String, String>> invalidPayloads() {
        return Map.ofEntries(
                Map.entry(NotificationKind.DAILY_TASK_APPROVED, Map.of()),
                Map.entry(NotificationKind.DAILY_TASK_REJECTED, Map.of("task", "تعقيم")),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_APPROVED, Map.of()),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_REJECTED, Map.of("task", "تعقيم")),
                Map.entry(NotificationKind.ACADEMY_SUBMISSION_VERIFIED, Map.of()),
                Map.entry(NotificationKind.ACADEMY_SUBMISSION_REJECTED, Map.of("unit", "تعقيم")),
                Map.entry(NotificationKind.INVENTORY_CHANGE_REQUESTED, Map.of()),
                Map.entry(NotificationKind.SUPPLIER_RETURN_REQUESTED, Map.of()),
                Map.entry(NotificationKind.DAILY_TASK_REVIEW_REQUESTED, Map.of()),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_REQUESTED, Map.of()),
                Map.entry(NotificationKind.ACADEMY_PHOTO_SUBMITTED, Map.of()),
                Map.entry(NotificationKind.PREP_CHECKLIST_REQUESTED, Map.of()),
                Map.entry(NotificationKind.PROCEDURE_CHANGE_REQUESTED, Map.of()),
                Map.entry(NotificationKind.USER_ACCESS_CHANGED, Map.of()),
                Map.entry(NotificationKind.EMPLOYEE_CHANGED, Map.of()),
                Map.entry(NotificationKind.CLINIC_SETTINGS_CHANGED, Map.of()));
    }

    private static Map<NotificationKind, String> invalidPayloadJsons() {
        return Map.ofEntries(
                Map.entry(NotificationKind.DAILY_TASK_APPROVED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.DAILY_TASK_REJECTED, "{\"actor\":\"مدير\",\"task\":\"تعقيم\"}"),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_APPROVED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_REJECTED, "{\"actor\":\"مدير\",\"task\":\"تعقيم\"}"),
                Map.entry(NotificationKind.ACADEMY_SUBMISSION_VERIFIED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.ACADEMY_SUBMISSION_REJECTED, "{\"actor\":\"مدير\",\"unit\":\"تعقيم\"}"),
                Map.entry(NotificationKind.INVENTORY_CHANGE_REQUESTED, "{\"actor\":\"مدير\",\"item\":\"قفازات\"}"),
                Map.entry(NotificationKind.SUPPLIER_RETURN_REQUESTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.DAILY_TASK_REVIEW_REQUESTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.TASK_ASSIGNMENT_REQUESTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.ACADEMY_PHOTO_SUBMITTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.PREP_CHECKLIST_REQUESTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.PROCEDURE_CHANGE_REQUESTED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.USER_ACCESS_CHANGED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.EMPLOYEE_CHANGED, "{\"actor\":\"مدير\"}"),
                Map.entry(NotificationKind.CLINIC_SETTINGS_CHANGED, "{\"actor\":\"مدير\"}"));
    }
}
