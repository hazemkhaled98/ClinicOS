package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import com.clinicos.shared.ActivityLogService;

class AdminActivityTemplateTest {

    private static final SpringTemplateEngine ENGINE = engine();

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext context() {
        return new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of());
    }

    @Test
    void filterChangesRefreshSelectedDayCategoryAndActivityListTogether() {
        WebContext context = context();
        context.setVariable("day", LocalDate.of(2026, 9, 19));
        context.setVariable("category", "all");
        context.setVariable("categories", Map.of("all", "الكل", "auth", "دخول النظام"));
        context.setVariable("entries", List.of());
        context.setVariable("labels", Map.of());

        String html = ENGINE.process("admin/activity", Set.of("activity"), context);

        assertThat(html).contains("id=\"activity-panel\"", "hx-target=\"#activity-panel\"",
                "hx-select=\"#activity-panel\"", "hx-trigger=\"change\"");
    }

    @Test
    void entriesShowTheirArabicLabelAndMarkTheActiveCategoryChip() {
        WebContext context = context();
        context.setVariable("day", LocalDate.of(2026, 9, 19));
        context.setVariable("category", "finance");
        context.setVariable("categories", Map.of("all", "الكل", "finance", "المالية"));
        context.setVariable("entries", List.of(
                new ActivityLogService.Entry(OffsetDateTime.of(2026, 9, 19, 10, 30, 0, 0, ZoneOffset.UTC),
                        "مدير العيادة", "inventory.order.place", "purchase_order", "finance"),
                new ActivityLogService.Entry(OffsetDateTime.of(2026, 9, 19, 11, 0, 0, 0, ZoneOffset.UTC),
                        null, "some.action.nobody.labelled", "widget", "finance")));
        context.setVariable("labels", Map.of("inventory.order.place", "إنشاء طلب شراء"));

        String html = ENGINE.process("admin/activity", Set.of("activity"), context);

        assertThat(html).contains("إنشاء طلب شراء", "مدير العيادة", "نظام");
        assertThat(html).contains("تسجيل نشاط").doesNotContain("some.action.nobody.labelled");
        assertThat(html).contains("aria-current=\"true\"");
    }
}
