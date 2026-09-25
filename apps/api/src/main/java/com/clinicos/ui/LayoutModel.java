package com.clinicos.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

/**
 * Builds the shared drawer/topbar/user data for every authenticated,
 * server-rendered page, from the clinic session primed by the
 * {@code identity} module's tenant-session filter plus the authenticated
 * principal. Session keys come from {@link SessionKeys}.
 */
@Component
public class LayoutModel {

    private static final Locale ARABIC = Locale.of("ar");

    public record LayoutData(List<NavSection> nav, String username, String clinicName, String role, String date, String activeRoute) {
    }

    public LayoutData forRequest(HttpSession httpSession, String activeRoute) {
        String roleCode = httpSession == null ? null
                : (String) httpSession.getAttribute(SessionKeys.ROLE_CODE);
        @SuppressWarnings("unchecked")
        List<String> codes = httpSession == null ? null
                : (List<String>) httpSession.getAttribute(SessionKeys.PERMISSIONS);
        String clinicName = httpSession == null ? null
                : (String) httpSession.getAttribute(SessionKeys.CLINIC_NAME);

        List<NavSection> nav = (roleCode == null || codes == null) ? List.of()
                : NavSectionResolver.resolve(new HashSet<>(codes), roleCode);

        return new LayoutData(nav, username(), clinicNameOrDefault(clinicName), roleDisplayName(roleCode), arabicLongDate(LocalDate.now()), activeRoute);
    }

    private static String username() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.getUsername();
        }
        return "";
    }

    private static String clinicNameOrDefault(String clinicName) {
        if (clinicName == null || clinicName.isBlank()) {
            return "عيادتي";
        }
        return clinicName;
    }

    public static String roleDisplayName(String roleCode) {
        if (roleCode == null) {
            return "مستخدم";
        }
        return switch (roleCode) {
            case "owner" -> "المالك";
            case "manager" -> "مدير";
            case "assistant" -> "مساعد";
            case "receptionist" -> "موظف استقبال";
            default -> "مستخدم";
        };
    }

    public static String arabicLongDate(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        Month month = date.getMonth();
        return day.getDisplayName(TextStyle.FULL, ARABIC) + " " + date.getDayOfMonth() + " "
                + month.getDisplayName(TextStyle.FULL, ARABIC);
    }
}
