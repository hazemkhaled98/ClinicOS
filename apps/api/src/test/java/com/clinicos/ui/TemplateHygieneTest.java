package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Enforces the ClinicOS UI hard rule (CLAUDE.md): a Thymeleaf template ships
 * zero inline {@code <style>} blocks, zero inline {@code <script>} bodies,
 * and zero {@code on*=} attributes. {@code fragments/head.html} is the only
 * file allowed to declare {@code <link>}/{@code <script src>} tags; every
 * other template must be pure markup that consumes the shared stylesheet and
 * behaviour scripts.
 */
class TemplateHygieneTest {

    private static final Pattern STYLE_BLOCK = Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT_BODY = Pattern.compile(
            "<script(?![^>]*\\bsrc=)[^>]*>\\s*\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_ATTRIBUTE = Pattern.compile(
            "\\son[a-z]+\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE);

    private static final String HEAD_FRAGMENT = "fragments" + Path.of("/").toString() + "head.html";

    @Test
    void noTemplateShipsInlineStyleScriptOrEventHandlers() throws IOException, URISyntaxException {
        List<Path> offenders = templateFiles()
                .filter(path -> !path.toString().replace('\\', '/').endsWith(HEAD_FRAGMENT))
                .filter(TemplateHygieneTest::hasInlineAsset)
                .toList();

        assertThat(offenders)
                .withFailMessage(() -> "Templates with inline <style>/<script>/on*= (must live in "
                        + "components.css, app.js, or fragments/head.html instead): " + offenders)
                .isEmpty();
    }

    private static boolean hasInlineAsset(Path path) {
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return STYLE_BLOCK.matcher(content).find()
                || INLINE_SCRIPT_BODY.matcher(content).find()
                || ON_ATTRIBUTE.matcher(content).find();
    }

    private static Stream<Path> templateFiles() throws IOException, URISyntaxException {
        URL templatesUrl = TemplateHygieneTest.class.getClassLoader().getResource("templates");
        assertThat(templatesUrl).as("templates/ must be on the test classpath").isNotNull();
        Path templatesDir = Paths.get(templatesUrl.toURI());
        return Files.walk(templatesDir).filter(p -> p.toString().endsWith(".html"));
    }

    private static final Pattern ARBITRARY_HEX_UTILITY = Pattern.compile("\\[#[0-9a-fA-F]{3,8}\\]");
    private static final Pattern STOCK_COLOR_RAMP = Pattern.compile(
            "(bg|text|border|ring|from|via|to|fill|stroke|divide|outline)-"
                    + "(slate|gray|zinc|stone|red|orange|amber|yellow|lime|green|blue|indigo|violet|purple|pink|rose|cyan|sky)-\\d+");

    @Test
    void noTemplateUsesOffPaletteColor() throws IOException, URISyntaxException {
        List<Path> offenders = templateFiles()
                .filter(TemplateHygieneTest::hasOffPaletteColor)
                .toList();

        assertThat(offenders)
                .withFailMessage(() -> "Templates using off-palette colors (only teal/emerald/neutral/danger/"
                        + "success/warning/info tokens from tokens.css are allowed): " + offenders)
                .isEmpty();
    }

    private static boolean hasOffPaletteColor(Path path) {
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ARBITRARY_HEX_UTILITY.matcher(content).find()
                || STOCK_COLOR_RAMP.matcher(content).find();
    }

    private static final Pattern PHYSICAL_DIRECTION_UTILITY = Pattern.compile(
            "\\b-?(pl|pr|ml|mr|border-l|border-r|translate-x|space-x|inset-x|left|right)-");

    @Test
    void noTemplateUsesPhysicalDirectionUtility() throws IOException, URISyntaxException {
        List<Path> offenders = templateFiles()
                .filter(path -> PHYSICAL_DIRECTION_UTILITY.matcher(readTemplate(path)).find())
                .toList();

        assertThat(offenders)
                .withFailMessage(() -> "Templates using physical-direction Tailwind utilities "
                        + "(use logical ps-/pe-/ms-/me-/border-s/border-e/start-/end- instead): " + offenders)
                .isEmpty();
    }

    private static String readTemplate(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
