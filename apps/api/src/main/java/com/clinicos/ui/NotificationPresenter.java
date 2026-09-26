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

    private static final Map<NotificationKind, String> LINKS = Map.of(
            NotificationKind.ACADEMY_SUBMISSION_VERIFIED, "/academy/me",
            NotificationKind.ACADEMY_SUBMISSION_REJECTED, "/academy/me",
            NotificationKind.DAILY_TASK_REJECTED, "/employees",
            NotificationKind.DAILY_TASK_APPROVED, "/employees",
            NotificationKind.TASK_ASSIGNMENT_APPROVED, "/employees",
            NotificationKind.TASK_ASSIGNMENT_REJECTED, "/employees",
            NotificationKind.INVENTORY_CHANGE_REQUESTED, "/inventory/approvals",
            NotificationKind.SUPPLIER_RETURN_REQUESTED, "/inventory/approvals");

    public List<Item> present(List<Notification> notifications) {
        return notifications.stream().map(n -> present(n)).toList();
    }

    public Item present(Notification notification) {
        NotificationKind kind = notification.kind();
        Map<String, String> payload = notification.payload();
        String actor = payload.getOrDefault("actor", "أحد المسؤولين");
        return switch (kind) {
            case DAILY_TASK_APPROVED -> item(notification, "اعتمد " + actor + " المهمة اليومية \"" + payload.get("task") + "\"");
            case DAILY_TASK_REJECTED -> item(notification, "رفض " + actor + " المهمة اليومية \"" + payload.get("task") + "\"", payload.get("reason"));
            case TASK_ASSIGNMENT_APPROVED -> item(notification, "اعتمد " + actor + " المهمة الإضافية \"" + payload.get("task") + "\"");
            case TASK_ASSIGNMENT_REJECTED -> item(notification, "رفض " + actor + " المهمة الإضافية \"" + payload.get("task") + "\"", payload.get("reason"));
            case ACADEMY_SUBMISSION_VERIFIED -> item(notification, "اعتمد " + actor + " إنجاز الوحدة التدريبية \"" + payload.get("unit") + "\"");
            case ACADEMY_SUBMISSION_REJECTED -> item(notification, "رفض " + actor + " إنجاز الوحدة التدريبية \"" + payload.get("unit") + "\"", payload.get("reason"));
            case INVENTORY_CHANGE_REQUESTED -> item(notification, "طلب " + actor + " " + payload.get("action") + " الصنف \"" + payload.get("item") + "\"");
            case SUPPLIER_RETURN_REQUESTED -> item(notification, "طلب " + actor + " إرجاع أصناف إلى المورد \"" + payload.get("supplier") + "\"");
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
