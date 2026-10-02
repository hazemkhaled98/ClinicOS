package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

class AdminActivityTemplateTest {

    @Test
    void filterChangesRefreshSelectedDayCategoryAndActivityListTogether() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of());
        context.setVariable("day", LocalDate.of(2026, 9, 19));
        context.setVariable("category", "all");
        context.setVariable("categories", Map.of("all", "الكل", "auth", "دخول النظام"));
        context.setVariable("entries", List.of());
        context.setVariable("labels", Map.of());

        String html = engine.process("admin/activity", Set.of("activity"), context);

        assertThat(html).contains("id=\"activity-panel\"", "hx-target=\"#activity-panel\"",
                "hx-select=\"#activity-panel\"", "hx-trigger=\"change\"");
    }
}
