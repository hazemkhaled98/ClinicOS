package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import com.clinicos.inventory.PurchasingService.Order;
import com.clinicos.inventory.PurchasingService.ReceiveLine;

class InventoryReturnsTemplateTest {

    @Test
    void zeroQuantityReturnCannotSubmitEmptyLineSet() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        OffsetDateTime now = OffsetDateTime.now();
        ReceiveLine line = new ReceiveLine(UUID.randomUUID(), UUID.randomUUID(), "صنف", "قطعة",
                new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ONE, null, null);
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "مورد", "received", now, now,
                null, BigDecimal.ONE, List.of(line));
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of(
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"),
                        "layout", new LayoutModel.LayoutData(List.of(), "مالك", "عيادتي", "owner", "2026-10-02", "inventory"),
                        "orders", List.of(order),
                        "returns", List.of()));

        String html = engine.process("inventory-returns", context);

        assertThat(html).contains("$el.querySelectorAll('[data-rrow]')", "$event.preventDefault()",
                "quantityError", "أدخل كمية واحدة على الأقل للإرجاع");
    }
}
