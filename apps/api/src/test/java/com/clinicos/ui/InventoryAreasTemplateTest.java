package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class InventoryAreasTemplateTest {

    @Test
    void everyInventoryAreaRendersItsOwnIcon() throws Exception {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of(
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"),
                        "layout", new LayoutModel.LayoutData(List.of(), "مالك", "عيادتي", "owner", "2026-10-02", "inventory"),
                        "areas", InventoryController.AREAS));

        String html = engine.process("inventory", context);

        assertThat(InventoryController.AREAS.stream().map(InventoryController.SubArea::code).distinct())
                .doesNotHaveDuplicates();
        for (InventoryController.SubArea area : InventoryController.AREAS) {
            assertThat(html).contains("href=\"/icons/inventory.svg#" + area.code() + "\"");
        }
        String sprite = Files.readString(
                Path.of("src/main/resources/static/icons/inventory.svg"), StandardCharsets.UTF_8);
        for (InventoryController.SubArea area : InventoryController.AREAS) {
            assertThat(sprite).contains("id=\"" + area.code() + "\"");
        }
    }
}