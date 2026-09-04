package com.clinicos.ui;

import java.util.HashSet;
import java.util.List;

import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.Cookie;

@Route(value = "", layout = MainLayout.class)
@PermitAll
public class HelloView extends VerticalLayout {

    public HelloView() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID) == null) {
            UI.getCurrent().navigate(ClinicPickerView.class);
            return;
        }

        String target = resolveTarget(
                readLastSectionCookie(),
                session.getAttribute(ClinicPickerView.SESSION_PERMISSIONS),
                session.getAttribute(ClinicPickerView.SESSION_ROLE_CODE));
        if (target != null) {
            UI.getCurrent().navigate(target);
            return;
        }

        H1 heading = new H1("ClinicOS");
        heading.getElement().setAttribute("dir", "rtl");
        add(heading);
    }

    @SuppressWarnings("unchecked")
    static String resolveTarget(String lastSectionCookie, Object codesAttr, Object roleCodeAttr) {
        if (!(codesAttr instanceof List<?> codesRaw) || !(roleCodeAttr instanceof String roleCode)) {
            return null;
        }
        List<String> codes = (List<String>) codesRaw;
        List<NavSection> sections = NavSectionResolver.resolve(new HashSet<>(codes), roleCode);
        if (sections.isEmpty()) {
            return null;
        }
        if (lastSectionCookie != null && sections.stream().anyMatch(s -> s.route().equals(lastSectionCookie))) {
            return lastSectionCookie;
        }
        return sections.getFirst().route();
    }

    private static String readLastSectionCookie() {
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        if (request == null) {
            return null;
        }
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