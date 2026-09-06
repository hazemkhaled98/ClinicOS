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
        title.addClassNames("text-base", "font-semibold", "text-slate-900", "clinicos-topbar-t2");

        Span date = new Span(arabicLongDate(LocalDate.now()));
        date.addClassNames("text-xs", "text-slate-500", "whitespace-nowrap", "clinicos-topbar-date");

        Div titles = new Div(title);
        titles.getStyle().set("flex", "1");

        HorizontalLayout topbar = new HorizontalLayout(menu, titles, date);
        topbar.addClassNames("h-16", "bg-white", "border-b", "border-slate-200", "px-6", "gap-4", "clinicos-topbar");
        topbar.setWidthFull();
        topbar.setAlignItems(Alignment.CENTER);
        return topbar;
    }

    private Div createDrawer() {
        Div brand = createBrandBlock();
        Div nav = createNav();
        Div who = createUserBlock();

        Div drawer = new Div(brand, nav, who);
        drawer.addClassNames("flex", "flex-col", "h-full", "bg-teal-900", "text-white", "w-72", "ps-0", "pe-0");
        return drawer;
    }

    private Div createBrandBlock() {
        Div logoBox = new Div();
        logoBox.addClassNames("w-11", "h-11", "rounded-xl", "bg-white", "shadow-md", "flex", "items-center", "justify-center", "flex-shrink-0");

        Span proBadge = new Span("PRO");
        proBadge.addClassNames("text-[10px]", "font-medium", "bg-emerald-500/20", "text-emerald-300", "border", "border-emerald-500/30", "px-1.5", "py-0.5", "rounded");

        Span title = new Span("عيادتي");
        title.addClassNames("text-lg", "font-bold", "tracking-tight", "text-white", "flex", "items-center", "gap-1.5", "clinicos-brand");
        title.add(proBadge);

        Span subtitle = new Span("نظام الإدارة المتكامل");
        subtitle.addClassNames("text-[11px]", "text-teal-300/60", "font-medium");

        Div titleStack = new Div(title, subtitle);
        titleStack.addClassNames("flex", "flex-col");

        Div logoSection = new Div(logoBox, titleStack);
        logoSection.addClassNames("flex", "items-center", "gap-3");

        Div brand = new Div(logoSection);
        brand.addClassNames("px-6", "pt-7", "pb-6", "flex", "items-center", "justify-between", "border-b", "border-teal-850/70");
        return brand;
    }

    private Div createNav() {
        VaadinSession session = VaadinSession.getCurrent();
        String roleCode = session == null ? null : (String) session.getAttribute(TenantSessionBinder.SESSION_ROLE_CODE);
        @SuppressWarnings("unchecked")
        List<String> codes = session == null ? null : (List<String>) session.getAttribute(TenantSessionBinder.SESSION_PERMISSIONS);

        nav = new Div();
        nav.addClassNames("flex-1", "overflow-y-auto", "ps-4", "pe-4", "py-6", "space-y-1.5", "clinicos-nav");

        if (roleCode == null || codes == null) {
            return nav;
        }
        for (NavSection section : NavSectionResolver.resolve(new HashSet<>(codes), roleCode)) {
            Div iconTile = new Div(new Icon(section.icon()));
            iconTile.addClassNames("w-8", "h-8", "rounded-lg", "bg-teal-800/40", "flex", "items-center", "justify-center", "text-teal-300", "clinicos-nav-icon");

            Span label = new Span(section.label());
            label.addClassNames("text-sm", "font-medium", "clinicos-nav-item-label");

            Span subtitle = new Span(section.subtitle());
            subtitle.addClassNames("text-xs", "font-normal", "text-teal-300/60", "clinicos-nav-item-sub");

            Div textStack = new Div(label, subtitle);
            textStack.addClassNames("flex", "flex-col");

            Button item = new Button();
            item.addClassNames("group", "flex", "items-center", "justify-between", "w-full", "px-3.5", "py-3", "rounded-xl", "text-teal-100/80", "hover:bg-teal-800/50", "hover:text-white", "transition-all", "duration-150", "bg-transparent", "border-none", "text-start", "cursor-pointer", "clinicos-nav-item");
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
        avatar.addClassNames("w-9", "h-9", "rounded-full", "bg-teal-700/60", "border", "border-emerald-400/40", "flex", "items-center", "justify-center", "text-teal-100", "font-bold", "text-xs", "flex-shrink-0", "relative", "clinicos-who-av");

        Span statusDot = new Span();
        statusDot.addClassNames("absolute", "bottom-0", "end-0", "w-2.5", "h-2.5", "rounded-full", "bg-emerald-400", "border-2", "border-teal-900");

        avatar.add(statusDot);

        Span nameLabel = new Span(name);
        nameLabel.addClassNames("text-xs", "font-bold", "text-white", "leading-tight", "clinicos-who-name");

        Span role = new Span(roleDisplayName());
        role.addClassNames("text-[11px]", "text-teal-300/70", "font-normal", "clinicos-who-role");

        Div meta = new Div(nameLabel, role);
        meta.addClassNames("flex", "flex-col", "clinicos-who-meta");

        Icon logoutIcon = new Icon(VaadinIcon.POWER_OFF);
        Span logout = new Span(logoutIcon);
        logout.addClassNames("w-8", "h-8", "rounded-lg", "bg-teal-800/50", "text-teal-300", "hover:text-white", "hover:bg-teal-800", "flex", "items-center", "justify-center", "transition-colors", "cursor-pointer", "clinicos-logout");
        logout.getElement().setAttribute("title", "تسجيل الخروج");
        logout.getElement().addEventListener("click", event -> authenticationContext.logout());

        Div userCard = new Div(avatar, meta, logout);
        userCard.addClassNames("flex", "items-center", "justify-between", "p-2", "rounded-xl", "bg-teal-900/50", "border", "border-teal-800/60", "hover:border-teal-700", "transition-colors");

        Div who = new Div(userCard);
        who.addClassNames("p-4", "border-t", "border-teal-850/80", "bg-teal-950/40", "clinicos-who");
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
