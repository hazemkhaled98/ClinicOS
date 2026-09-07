package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Enforces the ClinicOS RTL and design-token rules (CLAUDE.md, DESIGN.md) on
 * the source Tailwind stylesheets in {@code apps/api/src/main/styles/}.
 */
class CssHygieneTest {

    private static final Path STYLES_DIR = Paths.get("src/main/styles");
    private static final Path TOKENS_CSS = STYLES_DIR.resolve("tokens.css");
    private static final Path COMPONENTS_CSS = STYLES_DIR.resolve("components.css");
    private static final Path MAIN_CSS = STYLES_DIR.resolve("main.css");

    private static final Pattern PHYSICAL_CSS_PROPERTY = Pattern.compile(
            "\\b(left|right|padding-left|padding-right|margin-left|margin-right|border-left|border-right)\\s*:");

    private static final Pattern HEX_LITERAL = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

    private static final Set<String> ALLOWED_UNTOKENIZED_HEX = Set.of("#ffffff", "#fff");

    @Test
    void noCssFileUsesPhysicalDirectionProperty() throws IOException {
        for (Path css : List.of(TOKENS_CSS, COMPONENTS_CSS, MAIN_CSS)) {
            String content = readFile(css);
            assertThat(PHYSICAL_CSS_PROPERTY.matcher(content).find())
                    .withFailMessage(() -> css + " uses a physical-direction CSS property "
                            + "(use logical properties: padding-inline-start/end, margin-inline, "
                            + "border-inline-start/end, inset-inline)")
                    .isFalse();
        }
    }

    @Test
    void everyHexLiteralInComponentsOrMainIsTokenized() throws IOException {
        String tokensContent = readFile(TOKENS_CSS);

        for (Path css : List.of(COMPONENTS_CSS, MAIN_CSS)) {
            String content = readFile(css);
            Matcher matcher = HEX_LITERAL.matcher(content);
            while (matcher.find()) {
                String hex = matcher.group();
                boolean allowed = ALLOWED_UNTOKENIZED_HEX.contains(hex.toLowerCase(Locale.ROOT));
                boolean tokenized = tokensContent.toLowerCase(Locale.ROOT).contains(hex.toLowerCase(Locale.ROOT));
                assertThat(allowed || tokenized)
                        .withFailMessage(() -> css + " uses hex literal " + hex
                                + " which is neither defined in tokens.css nor in ALLOWED_UNTOKENIZED_HEX")
                        .isTrue();
            }
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
