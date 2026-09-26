package com.clinicos.ui;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationPresenter {

    public record Item(UUID id, String title, String detail, String href, boolean read, OffsetDateTime createdAt) {
    }

    private static final String HOME = "/";

    private static final Map<NotificationKind, String> LINKS = Map.ofEntries(
            Map.entry(NotificationKind.ACADEMY_SUBMISSION_VERIFIED, "/academy/me"),
            Map.entry(NotificationKind.ACADEMY_SUBMISSION_REJECTED, "/academy/me"),
            Map.entry(NotificationKind.DAILY_TASK_REJECTED, "/employees"),
            Map.entry(NotificationKind.DAILY_TASK_APPROVED, "/employees"),
            Map.entry(NotificationKind.TASK_ASSIGNMENT_APPROVED, "/employees"),
            Map.entry(NotificationKind.TASK_ASSIGNMENT_REJECTED, "/employees"),
            Map.entry(NotificationKind.INVENTORY_CHANGE_REQUESTED, "/inventory/approvals"),
            Map.entry(NotificationKind.SUPPLIER_RETURN_REQUESTED, "/inventory/approvals"),
            Map.entry(NotificationKind.DAILY_TASK_REVIEW_REQUESTED, "/evaluation"),
            Map.entry(NotificationKind.TASK_ASSIGNMENT_REQUESTED, "/evaluation"),
            Map.entry(NotificationKind.ACADEMY_PHOTO_SUBMITTED, "/academy/verify"),
            Map.entry(NotificationKind.PREP_CHECKLIST_REQUESTED, "/prep"),
            Map.entry(NotificationKind.PROCEDURE_CHANGE_REQUESTED, "/inventory/approvals"),
            Map.entry(NotificationKind.USER_ACCESS_CHANGED, "/admin-dashboard/users"),
            Map.entry(NotificationKind.EMPLOYEE_CHANGED, "/admin-dashboard"),
            Map.entry(NotificationKind.CLINIC_SETTINGS_CHANGED, "/admin-dashboard/settings"));

    public List<Item> present(List<Notification> notifications) {
        return notifications.stream().map(n -> present(n)).toList();
    }

    public Item present(Notification notification) {
        NotificationKind kind = notification.kind();
        Map<String, String> payload = notification.payload();
        String actor = payload.getOrDefault("actor", "أحد المسؤولين");
        return switch (kind) {
            case DAILY_TASK_APPROVED -> item(notification, "تم اعتماد المهمة اليومية \"" + payload.get("task") + "\" بواسطة " + actor);
            case DAILY_TASK_REJECTED -> item(notification, "تم رفض المهمة اليومية \"" + payload.get("task") + "\" بواسطة " + actor, payload.get("reason"));
            case TASK_ASSIGNMENT_APPROVED -> item(notification, "تم اعتماد المهمة الإضافية \"" + payload.get("task") + "\" بواسطة " + actor);
            case TASK_ASSIGNMENT_REJECTED -> item(notification, "تم رفض المهمة الإضافية \"" + payload.get("task") + "\" بواسطة " + actor, payload.get("reason"));
            case ACADEMY_SUBMISSION_VERIFIED -> item(notification, "تم اعتماد إنجاز الوحدة التدريبية \"" + payload.get("unit") + "\" بواسطة " + actor);
            case ACADEMY_SUBMISSION_REJECTED -> item(notification, "تم رفض إنجاز الوحدة التدريبية \"" + payload.get("unit") + "\" بواسطة " + actor, payload.get("reason"));
            case INVENTORY_CHANGE_REQUESTED -> item(notification, "تم طلب " + payload.get("action") + " الصنف \"" + payload.get("item") + "\" من " + actor);
            case SUPPLIER_RETURN_REQUESTED -> item(notification, "تم طلب إرجاع أصناف إلى المورد \"" + payload.get("supplier") + "\" من " + actor);
            case DAILY_TASK_REVIEW_REQUESTED -> item(notification, "بانتظار مراجعة المهمة اليومية \"" + payload.get("task") + "\" من " + actor);
            case TASK_ASSIGNMENT_REQUESTED -> item(notification, "بانتظار مراجعة المهمة الإضافية \"" + payload.get("task") + "\" من " + actor);
            case ACADEMY_PHOTO_SUBMITTED -> item(notification, "بانتظار التحقق من إنجاز الوحدة التدريبية \"" + payload.get("unit") + "\" من " + actor);
            case PREP_CHECKLIST_REQUESTED -> item(notification, "بانتظار اعتماد قائمة التحضير \"" + payload.get("checklist") + "\" من " + actor);
            case PROCEDURE_CHANGE_REQUESTED -> item(notification, "بانتظار اعتماد تعديل الإجراء \"" + payload.get("procedure") + "\" من " + actor);
            case USER_ACCESS_CHANGED -> item(notification, "تم تعديل صلاحيات المستخدم \"" + payload.get("user") + "\" بواسطة " + actor);
            case EMPLOYEE_CHANGED -> item(notification, "تم تعديل بيانات الموظف \"" + payload.get("employee") + "\" بواسطة " + actor);
            case CLINIC_SETTINGS_CHANGED -> item(notification, "تم تعديل إعدادات " + payload.get("area") + " بواسطة " + actor);
        };
    }

    private String linkFor(NotificationKind kind) {
        return LINKS.getOrDefault(kind, HOME);
    }

    private Item item(Notification notification, String title) {
        return new Item(notification.id(), title, "", linkFor(notification.kind()), notification.read(),
                notification.createdAt());
    }

    private Item item(Notification notification, String title, String reason) {
        String detail = reason == null || reason.isBlank() ? "" : "السبب: " + reason;
        return new Item(notification.id(), title, detail, linkFor(notification.kind()), notification.read(),
                notification.createdAt());
    }
}
