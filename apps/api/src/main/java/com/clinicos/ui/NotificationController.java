package com.clinicos.ui;

import java.util.List;
import java.util.UUID;

import com.clinicos.shared.NotificationService;
import com.clinicos.shared.NotificationService.Notification;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class NotificationController {

    private static final int RECENT_LIMIT = 20;

    private final NotificationService notificationService;
    private final NotificationPresenter presenter;

    public NotificationController(NotificationService notificationService, NotificationPresenter presenter) {
        this.notificationService = notificationService;
        this.presenter = presenter;
    }

    @GetMapping("/notifications/badge")
    public String badge(HttpSession session, Model model) {
        if (!loggedIn(session)) {
            return "fragments/notifications :: emptyBadge";
        }
        model.addAttribute("unread", notificationService.unreadCount(AdminAccess.clinicId(session),
                AdminAccess.membershipId(session)));
        return "fragments/notifications :: count";
    }

    @GetMapping("/notifications")
    public String list(HttpSession session, Model model) {
        if (!loggedIn(session)) {
            return "redirect:/login";
        }
        List<Notification> recent = notificationService.recent(AdminAccess.clinicId(session),
                AdminAccess.membershipId(session), RECENT_LIMIT);
        model.addAttribute("notifications", presenter.present(recent));
        model.addAttribute("unread", notificationService.unreadCount(AdminAccess.clinicId(session),
                AdminAccess.membershipId(session)));
        return "fragments/notifications :: list";
    }

    @PostMapping("/notifications/read-all")
    public String readAll(HttpSession session, Model model, HttpServletResponse response) {
        if (!loggedIn(session)) {
            return "redirect:/login";
        }
        notificationService.markAllRead(AdminAccess.clinicId(session), AdminAccess.membershipId(session));
        response.setHeader("HX-Trigger", "notificationsChanged");
        return list(session, model);
    }

    @PostMapping("/notifications/{id}/read")
    public void read(@PathVariable UUID id, HttpSession session, HttpServletResponse response) {
        if (!loggedIn(session)) {
            response.setHeader("HX-Redirect", "/login");
            return;
        }
        Notification read = notificationService.markRead(AdminAccess.clinicId(session),
                AdminAccess.membershipId(session), id);
        response.setHeader("HX-Redirect", presenter.present(read).href());
    }

    private static boolean loggedIn(HttpSession session) {
        return AdminAccess.clinicId(session) != null && AdminAccess.membershipId(session) != null;
    }
}
