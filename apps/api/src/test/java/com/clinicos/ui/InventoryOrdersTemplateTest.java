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
import com.clinicos.inventory.PurchasingService.Shortage;
import com.clinicos.inventory.PurchasingService.Supplier;

class InventoryOrdersTemplateTest {

    @Test
    void noSuppliersRendersOnlySupplierCallToAction() {
        String rendered = render(List.of(), List.of(), List.of());

        assertThat(rendered)
                .contains("لا توجد موردون", "إضافة مورّد")
                .doesNotContain("لا توجد نواقص", "لا توجد طلبيات معلّقة");
    }

    @Test
    void suppliersWithoutShortagesRenderNoShortagesState() {
        String rendered = render(List.of(supplier()), List.of(), List.of(order()));

        assertThat(rendered)
                .contains("لا توجد نواقص")
                .doesNotContain("لا توجد موردون", "لا توجد طلبيات معلّقة");
    }

    @Test
    void suppliersWithoutOrdersRenderNoPendingOrdersState() {
        String rendered = render(List.of(supplier()), List.of(shortage()), List.of());

        assertThat(rendered)
                .contains("لا توجد طلبيات معلّقة")
                .doesNotContain("لا توجد موردون", "لا توجد نواقص");
    }

    private String render(List<Supplier> suppliers, List<Shortage> shortages, List<Order> orders) {
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of(
                        "layout", new LayoutModel.LayoutData(List.of(), "مالك", "عيادتي", "owner", "", null),
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"),
                        "suppliers", suppliers,
                        "shortages", shortages,
                        "orders", orders));

        return templateEngine().process("inventory-orders", context);
    }

    private Supplier supplier() {
        return new Supplier(UUID.randomUUID(), "المورّد", "", "", 0, BigDecimal.ONE, null, false);
    }

    private Shortage shortage() {
        return new Shortage(UUID.randomUUID(), "الصنف", "قطعة", BigDecimal.ZERO, 1, null, null, BigDecimal.ONE, false);
    }

    private Order order() {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), "المورّد", "placed", OffsetDateTime.now(), null, null,
                BigDecimal.ONE, List.of());
    }

    private SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
