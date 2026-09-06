package com.clinicos.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.spring.security.AuthenticationContext;

import jakarta.annotation.security.PermitAll;

@Layout
@PermitAll
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private static final Locale ARABIC = Locale.of("ar");

    private final AuthenticationContext authenticationContext;
    private Div nav;

    /**
     * No-arg overload so Vaadin test tooling that instantiates layouts by
     * reflection (no Spring container, e.g. Karibu navigating to a view whose
     * {@code @Route} names this as its layout) has a constructor to call.
     * With two unannotated public constructors, Spring's autowiring
     * post-processor cannot pick one to favor and falls back to this one --
     * handing production code an unwired {@link AuthenticationContext} whose
     * {@code logout()} throws {@link NullPointerException}. {@link Autowired}
     * on the constructor below is what forces Spring to use it instead.
     */
    public MainLayout() {
        this(new AuthenticationContext());
    }

    @Autowired
    public MainLayout(AuthenticationContext authenticationContext) {
        this.authenticationContext = authenticationContext;
        getElement().setAttribute("dir", "rtl");
        addToNavbar(createTopbar());
        addToDrawer(createDrawer());
    }

    private HorizontalLayout createTopbar() {
        DrawerToggle menu = new DrawerToggle();
        menu.getElement().setAttribute("aria-label", "القائمة");

        Span title = new Span("عيادتي · إدارة الأداء");
        title.addClassName("clinicos-topbar-t2");

        Span date = new Span(arabicLongDate(LocalDate.now()));
        date.addClassName("clinicos-topbar-date");

        Div titles = new Div(title);
        titles.getStyle().set("flex", "1");

        HorizontalLayout topbar = new HorizontalLayout(menu, titles, date);
        topbar.addClassName("clinicos-topbar");
        topbar.setWidthFull();
        topbar.setAlignItems(Alignment.CENTER);
        return topbar;
    }

    private Div createDrawer() {
        Span brand = new Span("عيادتي");
        brand.addClassName("clinicos-brand");

        Div nav = createNav();

        Div who = createUserBlock();

        Div drawer = new Div(brand, nav, who);
        drawer.addClassName("clinicos-drawer");
        drawer.setSizeFull();
        return drawer;
    }

    private Div createNav() {
        VaadinSession session = VaadinSession.getCurrent();
        String roleCode = session == null ? null : (String) session.getAttribute(TenantSessionBinder.SESSION_ROLE_CODE);
        @SuppressWarnings("unchecked")
        List<String> codes = session == null ? null : (List<String>) session.getAttribute(TenantSessionBinder.SESSION_PERMISSIONS);

        nav = new Div();
        nav.addClassName("clinicos-nav");

        if (roleCode == null || codes == null) {
            return nav;
        }
        for (NavSection section : NavSectionResolver.resolve(new HashSet<>(codes), roleCode)) {
            Div iconTile = new Div(new Icon(section.icon()));
            iconTile.addClassName("clinicos-nav-icon");

            Span label = new Span(section.label());
            label.addClassName("clinicos-nav-item-label");

            Span subtitle = new Span(section.subtitle());
            subtitle.addClassName("clinicos-nav-item-sub");

            Div textStack = new Div(label, subtitle);

            Button item = new Button();
            item.addClassName("clinicos-nav-item");
            item.setWidthFull();
            item.getElement().setAttribute("data-route", section.route());
            item.getElement().appendChild(iconTile.getElement(), textStack.getElement());
            item.addClickListener(e -> UI.getCurrent().navigate(section.route()));
            nav.add(item);
        }
        return nav;
    }

    private Div createUserBlock() {
        String name = currentUsername();
        if (name == null) {
            name = "";
        }

        Span avatar = new Span(initialOf(name));
        avatar.addClassName("clinicos-who-av");

        Span nameLabel = new Span(name);
        nameLabel.addClassName("clinicos-who-name");

        Span role = new Span(roleDisplayName());
        role.addClassName("clinicos-who-role");

        Div meta = new Div(nameLabel, role);
        meta.addClassName("clinicos-who-meta");

        Icon logoutIcon = new Icon(VaadinIcon.POWER_OFF);
        Span logout = new Span(logoutIcon);
        logout.addClassName("clinicos-logout");
        logout.getElement().setAttribute("title", "تسجيل الخروج");
        logout.getElement().addEventListener("click", event -> authenticationContext.logout());

        Div who = new Div(avatar, meta, logout);
        who.addClassName("clinicos-who");
        return who;
    }

    private static String roleDisplayName() {
        VaadinSession session = VaadinSession.getCurrent();
        String roleCode = session == null ? null : (String) session.getAttribute(TenantSessionBinder.SESSION_ROLE_CODE);
        if (roleCode == null) {
            return "مستخدم";
        }
        return switch (roleCode) {
            case "owner" -> "المالك";
            case "manager" -> "مدير";
            default -> "مستخدم";
        };
    }

    private static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.getUsername();
        }
        return null;
    }

    private static String initialOf(String name) {
        return name.isEmpty() ? "؟" : String.valueOf(name.charAt(0));
    }

    static String arabicLongDate(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        Month month = date.getMonth();
        return day.getDisplayName(TextStyle.FULL, ARABIC) + " " + date.getDayOfMonth() + " "
                + month.getDisplayName(TextStyle.FULL, ARABIC);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        if (nav == null) {
            return;
        }
        String currentRoute = event.getLocation().getPath();
        for (int i = 0; i < nav.getComponentCount(); i++) {
            var child = nav.getComponentAt(i);
            String route = child.getElement().getAttribute("data-route");
            if (route != null && currentRoute.equals(route)) {
                child.getElement().setAttribute("aria-current", "page");
            } else {
                child.getElement().removeAttribute("aria-current");
            }
        }
    }
}
