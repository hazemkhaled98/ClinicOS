package com.clinicos.ui;

import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.server.VaadinServletResponse;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.Cookie;

@Route(value = "employees", layout = MainLayout.class)
@RouteAlias(value = "my-evaluation", layout = MainLayout.class)
@RouteAlias(value = "quick-access", layout = MainLayout.class)
@RouteAlias(value = "tasks", layout = MainLayout.class)
@RouteAlias(value = "prep", layout = MainLayout.class)
@RouteAlias(value = "academy", layout = MainLayout.class)
@RouteAlias(value = "inventory", layout = MainLayout.class)
@RouteAlias(value = "admin-dashboard", layout = MainLayout.class)
@PermitAll
public class SectionPlaceholderView extends VerticalLayout implements BeforeEnterObserver {

    public SectionPlaceholderView() {
        addClassName("clinicos-section");
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.START);
        setAlignItems(Alignment.STRETCH);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        String route = event.getLocation().getPath();
        writeCookie(route);
        NavSection section = NavSectionResolver.sectionByRoute(route);

        Paragraph headline = new Paragraph("عيادتي");
        headline.addClassName("clinicos-section-kicker");

        H1 title = new H1(section == null ? "القسم" : section.title());
        title.addClassName("clinicos-section-title");

        Paragraph subtitle = new Paragraph(section == null ? "" : section.subtitle());
        subtitle.addClassName("clinicos-section-sub");

        Paragraph soon = new Paragraph("سيتم تفعيل هذا القسم قريباً.");
        soon.addClassName("clinicos-section-soon");

        add(headline, title, subtitle, soon);
    }

    static void writeCookie(String route) {
        VaadinServletResponse response = VaadinServletResponse.getCurrent();
        if (response != null) {
            Cookie cookie = new Cookie("lastSection", route);
            cookie.setPath("/");
            cookie.setMaxAge(30 * 24 * 60 * 60);
            cookie.setHttpOnly(true);
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        }
    }
}