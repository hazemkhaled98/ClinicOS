package com.clinicos.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Authenticated app shell: root landing (redirect to the remembered or first
 * permitted section) and the placeholder pages for each drawer section.
 * Replaces the Vaadin {@code HelloView} / {@code MainLayout} /
 * {@code SectionPlaceholderView} triple.
 */
@Controller
public class AppController {

    private final LayoutModel layoutModel;

    public AppController(LayoutModel layoutModel) {
        this.layoutModel = layoutModel;
    }

    @GetMapping("/")
    public String root(HttpServletRequest request, HttpSession session, Model model) {
        if (session == null || session.getAttribute(SessionKeys.CLINIC_ID) == null) {
            model.addAttribute("layout", layoutModel.forRequest(session, null));
            model.addAttribute("message", "لا توجد عيادات مسجلة. لا تملك صلاحية الدخول إلى أي عيادة.");
            return "welcome/empty";
        }
        String target = SectionFocus.resolveTarget(
                readLastSectionCookie(request),
                session.getAttribute(SessionKeys.PERMISSIONS),
                session.getAttribute(SessionKeys.ROLE_CODE));
        if (target == null) {
            model.addAttribute("layout", layoutModel.forRequest(session, null));
            return "welcome/home";
        }
        return "redirect:/" + target;
    }

    @GetMapping("/{route}")
    public String section(@PathVariable String route, HttpServletRequest request,
            HttpServletResponse response, HttpSession session, Model model) {
        NavSection section = NavSectionResolver.sectionByRoute(route);
        if (section == null) {
            return "redirect:/";
        }
        LayoutModel.LayoutData layout = layoutModel.forRequest(session, route);
        if (!layout.nav().contains(section)) {
            return "redirect:/";
        }
        writeLastSectionCookie(response, route);
        model.addAttribute("layout", layout);
        model.addAttribute("section", section);
        model.addAttribute("message", "سيتم تفعيل هذا القسم قريباً.");
        return "section";
    }

    private static void writeLastSectionCookie(HttpServletResponse response, String route) {
        Cookie cookie = new Cookie("lastSection", route);
        cookie.setPath("/");
        cookie.setMaxAge(30 * 24 * 60 * 60);
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private static String readLastSectionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if ("lastSection".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
