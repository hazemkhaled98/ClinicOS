package com.clinicos.shared;

import java.util.Map;

public enum NotificationKind {

    DAILY_TASK_APPROVED,
    DAILY_TASK_REJECTED,
    TASK_ASSIGNMENT_APPROVED,
    TASK_ASSIGNMENT_REJECTED,
    ACADEMY_SUBMISSION_VERIFIED,
    ACADEMY_SUBMISSION_REJECTED,
    INVENTORY_CHANGE_REQUESTED,
    SUPPLIER_RETURN_REQUESTED,
    DAILY_TASK_REVIEW_REQUESTED,
    TASK_ASSIGNMENT_REQUESTED,
    ACADEMY_PHOTO_SUBMITTED,
    PREP_CHECKLIST_REQUESTED,
    PROCEDURE_CHANGE_REQUESTED,
    USER_ACCESS_CHANGED,
    EMPLOYEE_CHANGED,
    CLINIC_SETTINGS_CHANGED;

    public String literal() {
        return name();
    }

    public boolean hasValidPayload(Map<String, String> payload) {
        if (payload == null) {
            return false;
        }
        String subject = switch (this) {
            case DAILY_TASK_APPROVED, DAILY_TASK_REJECTED, DAILY_TASK_REVIEW_REQUESTED,
                    TASK_ASSIGNMENT_APPROVED, TASK_ASSIGNMENT_REJECTED,
                    TASK_ASSIGNMENT_REQUESTED -> payload.get("task");
            case ACADEMY_SUBMISSION_VERIFIED, ACADEMY_SUBMISSION_REJECTED,
                    ACADEMY_PHOTO_SUBMITTED -> payload.get("unit");
            case INVENTORY_CHANGE_REQUESTED -> payload.get("item");
            case SUPPLIER_RETURN_REQUESTED -> payload.get("supplier");
            case PREP_CHECKLIST_REQUESTED -> payload.get("checklist");
            case PROCEDURE_CHANGE_REQUESTED -> payload.get("procedure");
            case USER_ACCESS_CHANGED -> payload.get("user");
            case EMPLOYEE_CHANGED -> payload.get("employee");
            case CLINIC_SETTINGS_CHANGED -> payload.get("area");
        };
        return subject != null && !subject.isBlank()
                && (this != INVENTORY_CHANGE_REQUESTED || nonBlank(payload.get("action")))
                && (!isRejected() || payload.get("reason") != null && !payload.get("reason").isBlank());
    }

    public static NotificationKind fromLiteral(String literal) {
        for (NotificationKind kind : values()) {
            if (kind.literal().equals(literal)) {
                return kind;
            }
        }
        throw new MalformedNotificationDataException("نوع إشعار غير مدعوم");
    }

    private boolean isRejected() {
        return this == DAILY_TASK_REJECTED
                || this == TASK_ASSIGNMENT_REJECTED
                || this == ACADEMY_SUBMISSION_REJECTED;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
