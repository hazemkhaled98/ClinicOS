package com.clinicos.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

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
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.spring.security.AuthenticationContext;

import jakarta.annotation.security.PermitAll;

@Layout
@PermitAll
public class MainLayout extends AppLayout {

    private static final Locale ARABIC = Locale.of("ar");

    private final AuthenticationContext authenticationContext;

    /**
     * No-arg overload so Vaadin test tooling that instantiates layouts by
     * reflection (no Spring container, e.g. Karibu navigating to a view whose
     * {@code @Route} names this as its layout) has a constructor to call.
     * Spring's real container always resolves the greedier constructor below
     * instead, since {@link AuthenticationContext} is a bean it can satisfy.
     */
    public MainLayout() {
        this(new AuthenticationContext());
    }

    public MainLayout(AuthenticationContext authenticationContext) {
        this.authenticationContext = authenticationContext;
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
        String roleCode = session == null ? null : (String) session.getAttribute(ClinicPickerView.SESSION_ROLE_CODE);
        @SuppressWarnings("unchecked")
        List<String> codes = session == null ? null : (List<String>) session.getAttribute(ClinicPickerView.SESSION_PERMISSIONS);

        Div nav = new Div();
        nav.addClassName("clinicos-nav");

        if (roleCode == null || codes == null) {
            return nav;
        }
        for (NavSection section : NavSectionResolver.resolve(new HashSet<>(codes), roleCode)) {
            Button item = new Button(section.label(), e -> UI.getCurrent().navigate(section.route()));
            item.addClassName("clinicos-nav-item");
            item.setWidthFull();
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

        Span role = new Span("مستخدم");
        role.addClassName("clinicos-who-role");

        Div meta = new Div(nameLabel, role);
        meta.addClassName("clinicos-who-meta");

        Span logout = new Span("🚪");
        logout.addClassName("clinicos-logout");
        logout.getElement().setAttribute("title", "تسجيل الخروج");
        logout.getElement().addEventListener("click", event -> authenticationContext.logout());

        Div who = new Div(avatar, meta, logout);
        who.addClassName("clinicos-who");
        return who;
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
}
