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
        addClassName("clinicos-auth-page");
        getElement().setAttribute("dir", "rtl");

        loginForm = new LoginForm();
        clinicField = new TextField("كود العيادة");
        card = buildCard();

        add(buildPanel(), buildHero());
    }

    private Div buildHero() {
        Div hero = new Div();
        hero.addClassName("clinicos-auth-hero");

        Div badges = new Div();
        badges.addClassName("clinicos-auth-hero-badges");
        Span statusDot = new Span();
        statusDot.addClassName("clinicos-auth-badge-dot");
        Span sep = new Span("|");
        sep.addClassName("clinicos-auth-badge-sep");
        Span versionText = new Span("v3.2.0");
        versionText.addClassName("clinicos-auth-badge-accent");
        Span versionBadge = new Span(statusDot, new Span("نظام الإدارة السريرية المتقدم"), sep, versionText);
        versionBadge.addClassName("clinicos-auth-badge");
        badges.add(versionBadge, badge(VaadinIcon.SHIELD, "منصة مشفّرة ومطابقة لمعايير القطاع الصحي"));

        Div accentBar = new Div();
        accentBar.addClassName("clinicos-auth-hero-accent-bar");

        H1 title = new H1();
        title.addClassName("clinicos-auth-hero-title");
        Span titleAccent = new Span("بمعايير عالمية متكاملة");
        titleAccent.addClassName("clinicos-auth-hero-title-accent");
        Span titleLine = new Span("إدارة الكفاءة السريرية");
        titleLine.addClassName("clinicos-auth-hero-title-line");
        title.add(titleLine, titleAccent);

        Paragraph sub = new Paragraph(
                "متابعة مباشرة لمؤشرات الأداء، تدفق المراجعين، والإنتاجية الطبية في منصة موحدة "
                        + "مصممة لرفع كفاءة المنشآت الطبية الحديثة.");
        sub.addClassName("clinicos-auth-hero-sub");

        Div grid = new Div();
        grid.addClassName("clinicos-auth-feature-grid");
        grid.add(
                feature(VaadinIcon.LINE_CHART, "المؤشرات الحية", "مؤشرات الأداء السريري",
                        "تتبع آني لكفاءة الجلسات والعمليات"),
                feature(VaadinIcon.USERS, "مسار الاستقبال", "تدفق المراجعين الذكي",
                        "تقليص أوقات الانتظار والأشغال"),
                feature(VaadinIcon.COIN_PILES, "الربحية والتحكم", "الإنتاجية الطبية",
                        "ضبط الهدر والمخزون والتشغيل"));

        Div footer = new Div();
        footer.addClassName("clinicos-auth-hero-footer");
        footer.add(new Span("© 2025 عيادتي · ClinicOS · جميع الحقوق محفوظة"), new Span("الإصدار 3.2.0"));

        hero.add(badges, accentBar, title, sub, grid, footer);
        return hero;
    }

    private Span badge(VaadinIcon icon, String text) {
        Span badge = new Span();
        badge.addClassName("clinicos-auth-badge");
        if (icon != null) {
            badge.add(icon.create());
        }
        badge.add(new Span(text));
        return badge;
    }

    private Div feature(VaadinIcon icon, String kicker, String title, String description) {
        Div box = new Div();
        box.addClassName("clinicos-auth-feature");

        Icon featureIcon = icon.create();
        featureIcon.addClassName("clinicos-auth-feature-icon");

        Span kickerSpan = new Span(kicker);
        kickerSpan.addClassName("clinicos-auth-feature-kicker");
        Span titleSpan = new Span(title);
        titleSpan.addClassName("clinicos-auth-feature-title");

        box.add(featureIcon, kickerSpan, titleSpan, new Paragraph(description));
        return box;
    }

    private Div buildPanel() {
        Div panel = new Div();
        panel.addClassName("clinicos-auth-panel");
        panel.add(card);
        return panel;
    }

    private Div buildCard() {
        Div c = new Div();
        c.addClassName("clinicos-auth-card");

        Div logo = new Div();
        logo.addClassName("clinicos-auth-logo");
        logo.add(new Image(BRAND_LOGO_DATA_URI, "ClinicOS"));

        loginForm.setAction("login");
        loginForm.setI18n(createArabicLabels());
        loginForm.setForgotPasswordButtonVisible(false);

        clinicField.getElement().setAttribute("name", "clinic");
        clinicField.getElement().setAttribute("slot", "custom-form-area");
        clinicField.setRequiredIndicatorVisible(true);
        loginForm.getElement().appendChild(clinicField.getElement());

        Paragraph signupHint = new Paragraph(new RouterLink("ليس لديك حساب؟ أنشئ عيادة جديدة", SignupView.class));
        signupHint.addClassName("clinicos-auth-hint");

        Div securityNote = new Div(VaadinIcon.LOCK.create(), new Span("تسجيل آمن ومشفّر 256-bit"));
        securityNote.addClassName("clinicos-auth-security-note");

        c.add(logo, new H2("عيادتي · إدارة الأداء"), new Paragraph("سجّل دخولك للمتابعة"), loginForm, securityNote,
                signupHint);
        return c;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var params = event.getLocation().getQueryParameters();
        if (params.getParameters().containsKey("error")) {
            loginForm.setError(true);
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
