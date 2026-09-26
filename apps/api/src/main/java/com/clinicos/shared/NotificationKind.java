package com.clinicos.shared;

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

    public static NotificationKind fromLiteral(String literal) {
        if (literal == null) {
            return null;
        }
        for (NotificationKind kind : values()) {
            if (kind.literal().equals(literal)) {
                return kind;
            }
        }
        return null;
    }
}
