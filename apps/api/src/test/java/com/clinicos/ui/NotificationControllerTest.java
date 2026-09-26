package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.MalformedNotificationDataException;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.NotificationService.Notification;

import jakarta.servlet.http.HttpSession;

class NotificationControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    private NotificationService notificationService;
    private NotificationController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        controller = new NotificationController(notificationService, new NotificationPresenter());
        model = new ExtendedModelMap();
    }

    @Test
    void badgeRendersNothingWhenNotLoggedIn() {
        assertThat(controller.badge(anonymous(), model)).isEqualTo("fragments/notifications :: emptyBadge");
        verifyNoInteractions(notificationService);
    }

    @Test
    void badgeCarriesUnreadCount() {
        when(notificationService.unreadCount(CLINIC, MEMBERSHIP)).thenReturn(3);

        String view = controller.badge(session(), model);

        assertThat(view).isEqualTo("fragments/notifications :: count");
        assertThat(model.getAttribute("unread")).isEqualTo(3);
    }

    @Test
    void listRedirectsWhenNotLoggedIn() {
        assertThat(controller.list(anonymous(), model)).isEqualTo("redirect:/login");
        verifyNoInteractions(notificationService);
    }

    @Test
    void listMapsTitlesAndKeepsUnreadCount() {
        when(notificationService.recent(CLINIC, MEMBERSHIP, 20)).thenReturn(List.of(
                notification(NotificationKind.DAILY_TASK_REJECTED,
                        Map.of("actor", "المدير", "task", "تعقيم", "reason", "ناقصة"), false),
                notification(NotificationKind.ACADEMY_SUBMISSION_VERIFIED,
                        Map.of("actor", "المدير", "unit", "تعقيم الأدوات"), true)));
        when(notificationService.unreadCount(CLINIC, MEMBERSHIP)).thenReturn(1);

        String view = controller.list(session(), model);

        assertThat(view).isEqualTo("fragments/notifications :: list");
        assertThat(model.getAttribute("unread")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<NotificationPresenter.Item> items =
                (List<NotificationPresenter.Item>) model.getAttribute("notifications");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("تم رفض المهمة اليومية \"تعقيم\" بواسطة المدير");
        assertThat(items.get(0).detail()).isEqualTo("السبب: ناقصة");
        assertThat(items.get(0).href()).isEqualTo("/employees");
        assertThat(items.get(0).read()).isFalse();
        assertThat(items.get(1).title()).isEqualTo("تم اعتماد إنجاز الوحدة التدريبية \"تعقيم الأدوات\" بواسطة المدير");
        assertThat(items.get(1).href()).isEqualTo("/academy/me");
    }

    @Test
    void listRendersAnArabicErrorFragmentForInvalidNotificationData() {
        when(notificationService.recent(CLINIC, MEMBERSHIP, 20))
                .thenThrow(new MalformedNotificationDataException("بيانات الإشعار غير مكتملة"));

        String view = controller.list(session(), model);

        assertThat(view).isEqualTo("fragments/notifications :: error");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تعذر عرض الإشعارات");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
    }

    @Test
    void listPropagatesTenantInvariantFailures() {
        IllegalStateException failure = new IllegalStateException("No tenant bound matching clinic");
        when(notificationService.recent(CLINIC, MEMBERSHIP, 20)).thenThrow(failure);

        assertThatThrownBy(() -> controller.list(session(), model)).isSameAs(failure);
    }

    @Test
    void readAllMarksEverythingAndAsksBadgeToRefresh() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.readAll(session(), model, response);

        verify(notificationService).markAllRead(CLINIC, MEMBERSHIP);
        assertThat(response.getHeader("HX-Trigger")).isEqualTo("notificationsChanged");
        assertThat(view).isEqualTo("fragments/notifications :: list");
    }

    @Test
    void readRedirectsToServerDerivedRouteOfTheKind() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(notificationService.markRead(CLINIC, MEMBERSHIP, ID)).thenReturn(
                notification(NotificationKind.INVENTORY_CHANGE_REQUESTED,
                        Map.of("actor", "المساعد", "action", "تعديل", "item", "قفازات"), false));

        controller.read(ID, session(), response);

        verify(notificationService).markRead(CLINIC, MEMBERSHIP, ID);
        assertThat(response.getHeader("HX-Redirect")).isEqualTo("/inventory/approvals");
    }

    @Test
    void readRedirectsToLoginWhenSessionIsGone() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.read(ID, anonymous(), response);

        assertThat(response.getHeader("HX-Redirect")).isEqualTo("/login");
        verifyNoInteractions(notificationService);
    }

    @Test
    void requestLimitIsCappedByTheService() {
        when(notificationService.recent(CLINIC, MEMBERSHIP, 20)).thenReturn(List.of());
        when(notificationService.unreadCount(CLINIC, MEMBERSHIP)).thenReturn(0);

        controller.list(session(), model);

        verify(notificationService).recent(CLINIC, MEMBERSHIP, 20);
    }

    @Test
    void presenterRendersApprovedCopyForAllKinds() {
        NotificationPresenter presenter = new NotificationPresenter();
        var items = presenter.present(List.of(
                notification(NotificationKind.DAILY_TASK_APPROVED, Map.of("actor", "سارة", "task", "تعقيم"), false),
                notification(NotificationKind.DAILY_TASK_REJECTED,
                        Map.of("actor", "سارة", "task", "تعقيم", "reason", "ناقصة"), false),
                notification(NotificationKind.TASK_ASSIGNMENT_APPROVED, Map.of("actor", "سارة", "task", "جرد"), false),
                notification(NotificationKind.TASK_ASSIGNMENT_REJECTED,
                        Map.of("actor", "سارة", "task", "جرد", "reason", "غير مطلوب"), false),
                notification(NotificationKind.ACADEMY_SUBMISSION_VERIFIED, Map.of("actor", "سارة", "unit", "التعقيم"), false),
                notification(NotificationKind.ACADEMY_SUBMISSION_REJECTED,
                        Map.of("actor", "سارة", "unit", "التعقيم", "reason", "أعد الصورة"), false),
                notification(NotificationKind.INVENTORY_CHANGE_REQUESTED,
                        Map.of("actor", "سارة", "action", "تعديل", "item", "قفازات"), false),
                notification(NotificationKind.SUPPLIER_RETURN_REQUESTED,
                        Map.of("actor", "سارة", "supplier", "الريادة"), false),
                notification(NotificationKind.DAILY_TASK_REVIEW_REQUESTED,
                        Map.of("actor", "سارة", "task", "تعقيم"), false),
                notification(NotificationKind.TASK_ASSIGNMENT_REQUESTED,
                        Map.of("actor", "سارة", "task", "جرد"), false),
                notification(NotificationKind.ACADEMY_PHOTO_SUBMITTED,
                        Map.of("actor", "سارة", "unit", "التعقيم"), false),
                notification(NotificationKind.PREP_CHECKLIST_REQUESTED,
                        Map.of("actor", "سارة", "checklist", "قائمة البداية"), false),
                notification(NotificationKind.PROCEDURE_CHANGE_REQUESTED,
                        Map.of("actor", "سارة", "procedure", "حقن"), false),
                notification(NotificationKind.USER_ACCESS_CHANGED,
                        Map.of("actor", "سارة", "user", "أحمد"), false),
                notification(NotificationKind.EMPLOYEE_CHANGED,
                        Map.of("actor", "سارة", "employee", "مها"), false),
                notification(NotificationKind.CLINIC_SETTINGS_CHANGED,
                        Map.of("actor", "سارة", "area", "أوزان التقييم"), false)));

        assertThat(items).extracting(NotificationPresenter.Item::title).containsExactly(
                "تم اعتماد المهمة اليومية \"تعقيم\" بواسطة سارة",
                "تم رفض المهمة اليومية \"تعقيم\" بواسطة سارة",
                "تم اعتماد المهمة الإضافية \"جرد\" بواسطة سارة",
                "تم رفض المهمة الإضافية \"جرد\" بواسطة سارة",
                "تم اعتماد إنجاز الوحدة التدريبية \"التعقيم\" بواسطة سارة",
                "تم رفض إنجاز الوحدة التدريبية \"التعقيم\" بواسطة سارة",
                "تم طلب تعديل الصنف \"قفازات\" من سارة",
                "تم طلب إرجاع أصناف إلى المورد \"الريادة\" من سارة",
                "بانتظار مراجعة المهمة اليومية \"تعقيم\" من سارة",
                "بانتظار مراجعة المهمة الإضافية \"جرد\" من سارة",
                "بانتظار التحقق من إنجاز الوحدة التدريبية \"التعقيم\" من سارة",
                "بانتظار اعتماد قائمة التحضير \"قائمة البداية\" من سارة",
                "بانتظار اعتماد تعديل الإجراء \"حقن\" من سارة",
                "تم تعديل صلاحيات المستخدم \"أحمد\" بواسطة سارة",
                "تم تعديل بيانات الموظف \"مها\" بواسطة سارة",
                "تم تعديل إعدادات أوزان التقييم بواسطة سارة");
        assertThat(items).extracting(NotificationPresenter.Item::href).containsExactly(
                "/employees", "/employees", "/employees", "/employees", "/academy/me", "/academy/me",
                "/inventory/approvals", "/inventory/approvals",
                "/evaluation", "/evaluation", "/academy/verify", "/prep", "/inventory/approvals",
                "/admin-dashboard/users", "/admin-dashboard", "/admin-dashboard/settings");
        assertThat(items.get(1).detail()).isEqualTo("السبب: ناقصة");
        assertThat(items.get(3).detail()).isEqualTo("السبب: غير مطلوب");
        assertThat(items.get(5).detail()).isEqualTo("السبب: أعد الصورة");
    }

    private static Notification notification(NotificationKind kind, Map<String, String> payload, boolean read) {
        return new Notification(UUID.randomUUID(), kind, payload, OffsetDateTime.now(),
                read ? OffsetDateTime.now() : null);
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }

    private static HttpSession anonymous() {
        return mock(HttpSession.class);
    }
}
