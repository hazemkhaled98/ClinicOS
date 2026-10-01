package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService.Notification;

class NotificationPresenterTest {

    private final NotificationPresenter presenter = new NotificationPresenter();

    @Test
    void leaveNotificationCopiesAndLinksMatchRecipientAction() {
        var requested = presenter.present(notification(NotificationKind.LEAVE_REQUESTED,
                Map.of("employee", "سارة", "range", "12–14 أكتوبر")));
        var approved = presenter.present(notification(NotificationKind.LEAVE_APPROVED,
                Map.of("range", "12–14 أكتوبر", "actor", "المدير")));
        var rejected = presenter.present(notification(NotificationKind.LEAVE_REJECTED,
                Map.of("range", "12–14 أكتوبر", "actor", "المدير", "reason", "جدول مزدحم")));

        assertThat(requested.href()).isEqualTo("/leaves");
        assertThat(requested.title()).contains("سارة");
        assertThat(requested.detail()).contains("12–14 أكتوبر");
        assertThat(approved.href()).isEqualTo("/leaves/me");
        assertThat(approved.title()).contains("12–14 أكتوبر", "المدير");
        assertThat(rejected.href()).isEqualTo("/leaves/me");
        assertThat(rejected.title()).contains("12–14 أكتوبر", "المدير");
        assertThat(rejected.detail()).contains("جدول مزدحم");
    }

    private static Notification notification(NotificationKind kind, Map<String, String> payload) {
        return new Notification(UUID.randomUUID(), kind, payload, OffsetDateTime.now(), null);
    }
}
