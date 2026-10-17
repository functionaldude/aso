package com.functionaldude.aso

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TerminalUiE2ETest {
    @Test
    fun `terminal workbench supports keyboard navigation and narrow populated content`() {
        val artifacts = Path.of(System.getProperty("aso.test.artifacts")).resolve("goal-008/after")
        PackagedUiFixture(artifacts).use { fixture ->
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    UiScenario(browser, fixture.baseUrl).use { scenario ->
                        scenario.capture(artifacts)
                        scenario.inspectPilot(artifacts)
                        scenario.inspectApplication(artifacts)
                    }
                }
            }
        }
    }
}
