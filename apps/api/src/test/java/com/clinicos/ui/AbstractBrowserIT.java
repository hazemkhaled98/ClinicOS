package com.clinicos.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Base class for blackbox browser integration tests against the running app
 * (replaces the Vaadin-coupled {@code AbstractBasePlaywrightIT} from Drama
 * Finder). Each test method gets a fresh browser context + page; the app under
 * test is booted by the subclass's {@code @SpringBootTest} on a random port and
 * the subclass supplies the base URL via {@link #getUrl()}.
 */
public abstract class AbstractBrowserIT {

    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeEach
    void openBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true));
        page = browser.newPage();
        page.setViewportSize(1280, 800);
    }

    @AfterEach
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    protected Page page() {
        return page;
    }

    protected void open(String path) {
        page.navigate(getUrl() + path);
    }

    protected abstract String getUrl();
}