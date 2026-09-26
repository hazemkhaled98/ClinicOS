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
    SUPPLIER_RETURN_REQUESTED;

    public String literal() {
        return name();
    }

    public boolean hasValidPayload(Map<String, String> payload) {
        if (payload == null) {
            return false;
        }
        String subject = switch (this) {
            case DAILY_TASK_APPROVED, DAILY_TASK_REJECTED,
                    TASK_ASSIGNMENT_APPROVED, TASK_ASSIGNMENT_REJECTED -> payload.get("task");
            case ACADEMY_SUBMISSION_VERIFIED, ACADEMY_SUBMISSION_REJECTED -> payload.get("unit");
            case INVENTORY_CHANGE_REQUESTED -> payload.get("item");
            case SUPPLIER_RETURN_REQUESTED -> payload.get("supplier");
        };
        return subject != null && !subject.isBlank()
                && (!isRejected() || payload.get("reason") != null && !payload.get("reason").isBlank());
    }

    public static NotificationKind fromLiteral(String literal) {
        for (NotificationKind kind : values()) {
            if (kind.literal().equals(literal)) {
                return kind;
            }
        }
        throw new IllegalStateException("نوع إشعار غير مدعوم");
    }

    private boolean isRejected() {
        return this == DAILY_TASK_REJECTED
                || this == TASK_ASSIGNMENT_REJECTED
                || this == ACADEMY_SUBMISSION_REJECTED;
    }
}
