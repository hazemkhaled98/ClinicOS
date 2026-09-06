package com.clinicos.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login screen for ClinicOS: an embedded split-screen page (marketing hero +
 * login card) built around Vaadin's {@link LoginForm}. Unlike {@code LoginOverlay},
 * {@link LoginForm} renders inline rather than as a modal dialog, which is what
 * an embedded page needs. The form posts to {@code /login}
 * ({@link LoginForm#setAction}), which Spring Security's form-login processes.
 * A clinic-code field is added as a light-DOM child of the login form with
 * {@code slot="custom-form-area"}, which is how the underlying web component
 * includes extra fields in the POST (see {@code vaadin-login-form-mixin.js}).
 * RTL layout for Arabic UI.
 */
@Route(value = "login", autoLayout = false)
@AnonymousAllowed
public class LoginView extends Div implements BeforeEnterObserver {

    static final String BRAND_LOGO_DATA_URI = loadBrandLogoDataUri();

    private static String loadBrandLogoDataUri() {
        try (InputStream in = LoginView.class.getResourceAsStream("/branding/logo.png")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final LoginForm loginForm;
    private final TextField clinicField;
    private final Div card;

    public LoginView() {
        addClassNames("min-h-screen", "w-full", "flex", "flex-col", "lg:flex-row");
        addClassName("relative");
        getElement().setAttribute("dir", "rtl");
        getStyle().set("background-color", "#f6faf8");
        getStyle().set("background-image",
                "radial-gradient(at 85% 15%, rgba(16, 185, 129, 0.08) 0px, transparent 50%), " +
                "radial-gradient(at 10% 90%, rgba(10, 46, 41, 0.05) 0px, transparent 50%), " +
                "linear-gradient(rgba(10, 46, 41, 0.03) 1px, transparent 1px), " +
                "linear-gradient(90deg, rgba(10, 46, 41, 0.03) 1px, transparent 1px)");
        getStyle().set("background-size", "100% 100%, 100% 100%, 32px 32px, 32px 32px");

        loginForm = new LoginForm();
        clinicField = new TextField("كود العيادة");
        card = buildCard();

        add(buildHero(), buildPanel());
    }

    private Div buildHero() {
        Div hero = new Div();
        hero.addClassNames("lg:w-7/12", "w-full", "subtle-mesh", "relative", "flex", "flex-col",
                "justify-between", "p-8", "sm:p-14", "lg:p-20");

        Div badges = new Div();
        badges.addClassNames("flex", "flex-wrap", "items-center", "justify-between", "gap-4", "w-full");

        Span statusDot = new Span();
        statusDot.addClassNames("w-2", "h-2", "rounded-full", "bg-emerald-500", "flex-shrink-0");
        Span sep = new Span("|");
        sep.addClassNames("text-slate-300");
        Span versionText = new Span("v3.2.0");
        versionText.addClassNames("text-emerald-700", "font-normal");
        Span versionBadge = new Span(statusDot, new Span("نظام الإدارة السريرية المتقدم"), sep, versionText);
        versionBadge.addClassNames("inline-flex", "items-center", "gap-2.5", "px-4", "py-1.5",
                "rounded-full", "bg-white/90", "border", "border-emerald-200/60", "shadow-sm",
                "text-xs", "font-semibold", "text-teal-900");

        badges.add(versionBadge, badge(VaadinIcon.SHIELD, "منصة مشفّرة ومطابقة لمعايير القطاع الصحي"));

        Div accentBar = new Div();
        accentBar.addClassNames("w-12", "h-1.5", "bg-gradient-to-r", "from-emerald-500",
                "to-teal-900", "rounded-full", "mb-6");

        H1 title = new H1();
        title.addClassNames("text-3xl", "sm:text-4xl", "lg:text-5xl", "font-extrabold",
                "text-teal-900", "leading-[1.25]", "tracking-tight", "m-0");
        Span titleAccent = new Span("بمعايير عالمية متكاملة");
        titleAccent.addClassNames("block", "text-transparent", "bg-clip-text",
                "bg-gradient-to-l", "from-emerald-600", "to-teal-900");
        Span titleLine = new Span("إدارة الكفاءة السريرية");
        titleLine.addClassNames("block");
        title.add(titleLine, titleAccent);

        Paragraph sub = new Paragraph(
                "متابعة مباشرة لمؤشرات الأداء، تدفق المراجعين، والإنتاجية الطبية في منصة موحدة "
                        + "مصممة لرفع كفاءة المنشآت الطبية الحديثة.");
        sub.addClassNames("mt-6", "text-base", "sm:text-lg", "text-slate-600", "leading-relaxed",
                "font-normal", "m-0");

        Div grid = new Div();
        grid.addClassNames("grid", "grid-cols-1", "sm:grid-cols-3", "gap-4", "mt-10");
        grid.add(
                feature(VaadinIcon.LINE_CHART, "المؤشرات الحية", "مؤشرات الأداء السريري",
                        "تتبع آني لكفاءة الجلسات والعمليات"),
                feature(VaadinIcon.USERS, "مسار الاستقبال", "تدفق المراجعين الذكي",
                        "تقليص أوقات الانتظار والأشغال"),
                feature(VaadinIcon.COIN_PILES, "الربحية والتحكم", "الإنتاجية الطبية",
                        "ضبط الهدر والمخزون والتشغيل"));

        Div footer = new Div();
        footer.addClassNames("w-full", "flex", "items-center", "justify-between", "text-xs",
                "text-slate-400", "border-t", "border-slate-200/80", "pt-6");
        Span versionFooter = new Span("الإصدار 3.2.0");
        versionFooter.addClassNames("font-medium");
        Span copyrightFooter = new Span("© 2025 عيادتي ClinicOS · جميع الحقوق محفوظة");
        footer.add(versionFooter, copyrightFooter);

        Div centerContent = new Div();
        centerContent.addClassNames("my-auto", "py-12", "max-w-2xl");
        centerContent.add(accentBar, title, sub, grid);

        hero.add(badges, centerContent, footer);
        return hero;
    }

    private Span badge(VaadinIcon icon, String text) {
        Span badge = new Span();
        badge.addClassNames("inline-flex", "items-center", "gap-2", "px-3.5", "py-1.5",
                "rounded-full", "bg-emerald-50/80", "border", "border-emerald-200", "text-xs",
                "font-semibold", "text-teal-900");
        if (icon != null) {
            Icon vaadinIcon = icon.create();
            vaadinIcon.addClassNames("w-4", "h-4", "text-emerald-600");
            badge.add(vaadinIcon);
        }
        badge.add(new Span(text));
        return badge;
    }

    private Div feature(VaadinIcon icon, String kicker, String title, String description) {
        Div box = new Div();
        box.addClassNames("auth-glass-card", "rounded-2xl", "p-5", "shadow-sm",
                "hover:shadow-md", "transition-shadow", "group");

        Icon featureIcon = icon.create();
        featureIcon.addClassNames("w-10", "h-10", "rounded-xl", "bg-emerald-100/70",
                "text-teal-900", "flex", "items-center", "justify-center", "mb-3",
                "group-hover:scale-105", "transition-transform");

        Span kickerSpan = new Span(kicker);
        kickerSpan.addClassNames("block", "text-xs", "font-bold", "text-slate-400", "mb-1");
        Span titleSpan = new Span(title);
        titleSpan.addClassNames("block", "text-sm", "font-bold", "text-teal-900");
        Paragraph descPara = new Paragraph(description);
        descPara.addClassNames("text-[11px]", "text-slate-500", "mt-1", "m-0");

        box.add(featureIcon, kickerSpan, titleSpan, descPara);
        return box;
    }

    private Div buildPanel() {
        Div panel = new Div();
        panel.addClassNames("lg:w-5/12", "w-full", "relative", "flex", "flex-col", "items-center",
                "justify-center", "p-6", "sm:p-12", "lg:p-16", "shadow-2xl", "z-10",
                "border-e", "border-emerald-500/20");
        panel.getStyle().set("background", "radial-gradient(circle at 10% 20%, #0d3832 0%, #0a2e29 60%, #061e1b 100%)");

        // Ambient glow orbs
        Div orb1 = new Div();
        orb1.addClassNames("absolute", "top-1/4", "start-[-5rem]", "w-72", "h-72",
                "bg-emerald-500/15", "rounded-full", "blur-3xl", "pointer-events-none");
        Div orb2 = new Div();
        orb2.addClassNames("absolute", "bottom-10", "end-0", "w-64", "h-64",
                "bg-teal-900/50", "rounded-full", "blur-2xl", "pointer-events-none");

        panel.add(orb1, orb2, card);
        return panel;
    }

    private Div buildCard() {
        Div c = new Div();
        c.addClassNames("w-full", "max-w-[420px]", "bg-white", "rounded-3xl", "p-8", "sm:p-10",
                "shadow-2xl", "relative", "z-10", "border", "border-emerald-50");

        Div logoContainer = new Div();
        logoContainer.addClassNames("flex", "flex-col", "items-center", "text-center", "mb-8");
        Div logoBox = new Div();
        logoBox.addClassNames("w-24", "h-24", "mb-6", "flex", "items-center", "justify-center",
                "bg-white", "rounded-2xl", "shadow-lg", "border", "border-slate-200/80", "p-1.5");
        Image logo = new Image(BRAND_LOGO_DATA_URI, "ClinicOS");
        logo.addClassNames("object-contain", "drop-shadow", "w-full", "h-full");
        logoBox.add(logo);

        H2 heading = new H2("عيادتي · إدارة الأداء");
        heading.addClassNames("text-2xl", "font-bold", "tracking-tight", "text-teal-900", "m-0");
        Paragraph subheading = new Paragraph("سجّل دخولك للمتابعة");
        subheading.addClassNames("text-sm", "font-medium", "text-slate-500", "mt-1", "m-0");

        logoContainer.add(logoBox, heading, subheading);

        loginForm.setAction("login");
        loginForm.setI18n(createArabicLabels());
        loginForm.setForgotPasswordButtonVisible(false);

        clinicField.getElement().setAttribute("name", "clinic");
        clinicField.getElement().setAttribute("slot", "custom-form-area");
        clinicField.setRequiredIndicatorVisible(true);
        loginForm.getElement().appendChild(clinicField.getElement());

        Div securityNote = new Div();
        securityNote.addClassNames("mt-8", "pt-5", "border-t", "border-slate-100", "flex",
                "items-center", "justify-center", "gap-2", "text-xs", "text-slate-400", "font-medium");
        Icon lockIcon = VaadinIcon.LOCK.create();
        lockIcon.addClassNames("w-3.5", "h-3.5", "text-emerald-600");
        securityNote.add(lockIcon, new Span("تسجيل آمن ومشفّر 256-bit"));

        Paragraph signupHint = new Paragraph(new RouterLink("ليس لديك حساب؟ أنشئ عيادة جديدة", SignupView.class));
        signupHint.addClassNames("text-slate-600", "text-xs", "mt-6", "text-center", "m-0");
        signupHint.getStyle().set("font-size", "12px");

        c.add(logoContainer, loginForm, securityNote, signupHint);
        return c;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var params = event.getLocation().getQueryParameters();
        if (params.getParameters().containsKey("error")) {
            loginForm.setError(true);
            card.addClassName("clinicos-auth-error");
        }
        params.getSingleParameter("clinic").ifPresent(clinicField::setValue);
        if (params.getSingleParameter("signup").map("success"::equals).orElse(false)) {
            Paragraph banner = new Paragraph("تم إنشاء العيادة بنجاح، سجّل الدخول لبدء العمل");
            banner.addClassName("clinicos-banner-success");
            card.add(banner);
        }
    }

    private LoginI18n createArabicLabels() {
        LoginI18n i18n = new LoginI18n();

        LoginI18n.Form form = new LoginI18n.Form();
        form.setUsername("اسم المستخدم");
        form.setPassword("كلمة المرور");
        form.setSubmit("دخول");
        form.setTitle("تسجيل الدخول");
        i18n.setForm(form);

        LoginI18n.ErrorMessage errorMessage = new LoginI18n.ErrorMessage();
        errorMessage.setTitle("❌");
        errorMessage.setMessage("بيانات الدخول غير صحيحة");
        i18n.setErrorMessage(errorMessage);

        return i18n;
    }
}
