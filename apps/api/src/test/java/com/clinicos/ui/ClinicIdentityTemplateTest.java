package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ClinicIdentityTemplateTest {

    private static String render(String template, Set<String> fragments, Context context) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine.process(template, fragments, context);
    }

    private static String template(String path) throws Exception {
        Path file = Path.of("src/main/resources/templates", path);
        assertThat(Files.exists(file)).as(file.toString()).isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void invalidSlugIsExplainedBesideTheFieldAfterSubmit() {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("عيادة النور");
        form.setSlug("غير-صالح");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of("slug", "استخدم أحرفًا إنجليزية"));

        String html = render("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).contains("استخدم أحرفًا إنجليزية", "aria-invalid=\"true\"",
                "aria-describedby=\"clinic-slug-help clinic-slug-error\"", "value=\"غير-صالح\"");
    }

    @Test
    void aSavedCardCarriesTheNameSoChromeCanUpdateWithoutAReload() {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("عيادة النور");
        form.setSlug("bright-smile");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of());

        String html = render("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).contains("data-clinic-name=\"عيادة النور\"");
    }

    @Test
    void aRejectedCardCarriesNoNameSoChromeKeepsTheSavedOne() {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("اسم لم يُحفظ");
        form.setSlug("-bad-slug-");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of("slug", "كود غير صالح"));

        String html = render("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).doesNotContain("data-clinic-name");
    }

    @Test
    void theNameSyncRunsOnlyForTheIdentityCardItselfAndConsumesTheAttribute() throws Exception {
        String head = template("fragments/head.html");

        assertThat(head).contains("e.detail.elt", "matches('[data-clinic-name]')",
                "removeAttribute('data-clinic-name')");
        assertThat(head).doesNotContain("document.querySelector('[data-clinic-name]')");
    }

    @Test
    void topbarKeepsTheProductSuffixOutsideTheSwappableNameNode() {
        Context context = new Context(Locale.ROOT);
        context.setVariable("layout", Map.of("clinicName", "Test Clinic", "date", "2026-09-27"));

        String html = render("fragments/topbar", Set.of("topbar"), context);

        Matcher name = Pattern.compile("<span\\b[^>]*class=\"clinicos-clinic-name\"[^>]*>([^<]*)</span>")
                .matcher(html);
        assertThat(name.find()).isTrue();
        assertThat(name.group(1)).isEqualTo("Test Clinic");
        assertThat(html).contains("· إدارة الأداء");
    }

    @Test
    void everyNameInChromeIsMarkedSoARenameReachesAllOfThem() throws Exception {
        assertThat(template("fragments/drawer.html")).contains("class=\"clinicos-clinic-name\"");
        assertThat(template("admin/settings.html")).contains("clinicos-clinic-name");
    }
}
