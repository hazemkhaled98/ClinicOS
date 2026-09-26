package com.clinicos.shared;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface NotificationService {

    int MAX_LIMIT = 100;

    Set<String> APPROVER_ROLES = Set.of("owner", "manager");

    record Notification(
            UUID id,
            NotificationKind kind,
            Map<String, String> payload,
            OffsetDateTime createdAt,
            OffsetDateTime readAt) {

        public Notification {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public boolean read() {
            return readAt != null;
        }
    }

    int notifyMembership(UUID clinicId, UUID actorMembershipId, UUID membershipId, NotificationKind kind,
            Map<String, String> payload);

    int notifyEmployee(UUID clinicId, UUID actorMembershipId, UUID employeeId, NotificationKind kind,
            Map<String, String> payload);

    int notifyRoles(UUID clinicId, UUID actorMembershipId, Set<String> roleCodes, NotificationKind kind,
            Map<String, String> payload);

    int unreadCount(UUID clinicId, UUID membershipId);

    List<Notification> recent(UUID clinicId, UUID membershipId, int limit);

    Notification markRead(UUID clinicId, UUID membershipId, UUID notificationId);

    int markAllRead(UUID clinicId, UUID membershipId);
}
