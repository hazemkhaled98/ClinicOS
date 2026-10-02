package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.clinicos.academy.AcademyService;

class AcademyLearnerTemplateTest {

    private static final SpringTemplateEngine ENGINE = engine();

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext learnerContext(boolean isOwnCurriculum) {
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of(
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"),
                        "layout", new LayoutModel.LayoutData(List.of(), "مالك", "عيادتي", "owner", "2026-10-02", "academy"),
                        "track", new AcademyService.TraineeTrack(UUID.randomUUID(), "متدرب", List.of(
                                new AcademyService.TraineeUnit(UUID.randomUUID(), null, "📘", "وحدة", "هدف",
                                        List.of(), null, false, 0, "open")))));
        context.setVariable("isOwnCurriculum", isOwnCurriculum);
        return context;
    }

    @Test
    void anotherLearnersCurriculumDoesNotOfferCompletionExamOrCertificateActions() {
        String html = ENGINE.process("academy-learner", learnerContext(false));

        assertThat(html).doesNotContain("/academy/units/", "/academy/exam", "/academy/certificate", "إتمام الوحدة")
                .contains("عرض التقدم فقط");
    }

    @Test
    void ownCurriculumOffersCompletionExamAndCertificateActions() {
        String html = ENGINE.process("academy-learner", learnerContext(true));

        assertThat(html).contains("/academy/units/", "/academy/exam", "/academy/certificate", "إتمام الوحدة")
                .doesNotContain("عرض التقدم فقط");
    }
}
