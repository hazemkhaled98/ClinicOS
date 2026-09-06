package com.clinicos.ui;

import java.util.HashSet;
import java.util.List;

import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.Cookie;

@Route(value = "", layout = MainLayout.class)
@PermitAll
public class HelloView extends VerticalLayout implements BeforeEnterObserver {

    public HelloView() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID) == null) {
            add(emptyState());
            return;
        }

        H1 heading = new H1("ClinicOS");
        heading.getElement().setAttribute("dir", "rtl");
        add(heading);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID) == null) {
            return;
        }
        String target = resolveTarget(
                readLastSectionCookie(),
                session.getAttribute(TenantSessionBinder.SESSION_PERMISSIONS),
                session.getAttribute(TenantSessionBinder.SESSION_ROLE_CODE));
        if (target != null) {
            event.forwardTo(target);
        }
    }

    private static Div emptyState() {
        Icon icon = new Icon(VaadinIcon.EXCLAMATION_CIRCLE);
        Paragraph message = new Paragraph("لا توجد عيادات مسجلة. لا تملك صلاحية الدخول إلى أي عيادة.");

        Div emptyState = new Div(icon, message);
        emptyState.addClassName("clinicos-empty-state");
        icon.addClassName("clinicos-empty-state-icon");
        message.addClassName("clinicos-empty-state-message");
        return emptyState;
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