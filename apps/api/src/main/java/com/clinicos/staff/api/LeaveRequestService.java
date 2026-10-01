package com.clinicos.staff.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LeaveRequestService {

    LeaveRequest submit(UUID clinicId, UUID employeeId, LocalDate start, LocalDate end, String reason,
            UUID actorMembershipId);

    LeaveRequest approve(UUID clinicId, UUID leaveRequestId, UUID actorMembershipId);

    LeaveRequest reject(UUID clinicId, UUID leaveRequestId, String note, UUID actorMembershipId);

    void cancel(UUID clinicId, UUID leaveRequestId, UUID employeeId, UUID actorMembershipId);

    List<LeaveRequest> listPendingForApprover(UUID clinicId, UUID actorMembershipId, String actorRoleCode);

    List<LeaveRequest> listForEmployee(UUID clinicId, UUID employeeId);

    boolean hasOtherActiveOwner(UUID clinicId, UUID actorMembershipId);

    static boolean mayDecide(String actorRoleCode, String requesterRoleCode, boolean self) {
        if (self) {
            return false;
        }
        if ("owner".equals(actorRoleCode)) {
            return true;
        }
        return "manager".equals(actorRoleCode)
                && !"owner".equals(requesterRoleCode)
                && !"manager".equals(requesterRoleCode);
    }

    record LeaveRequest(
            UUID id,
            UUID employeeId,
            String employeeName,
            String requesterRoleCode,
            LocalDate start,
            LocalDate end,
            String reason,
            LeaveStatus status,
            UUID decidedByMembershipId,
            String decisionNote,
            java.time.OffsetDateTime createdAt) {
    }

    enum LeaveStatus {
        pending,
        approved,
        rejected;

        public String literal() {
            return name();
        }

        public static LeaveStatus of(String literal) {
            for (LeaveStatus status : values()) {
                if (status.literal().equals(literal)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("حالة طلب إجازة غير معروفة");
        }
    }
}
