package com.jorisjonkers.personalstack.common.test.system

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(FqcnShardCondition::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class PlaywrightSystemTestBase {
    protected val environment: SystemTestEnvironment = SystemTestEnvironment.current
    protected val apiUrl: String = environment.apiUrl
    protected val frontendUrl: String = environment.frontendUrl

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    protected lateinit var context: BrowserContext
    protected lateinit var page: Page

    @BeforeAll
    fun launchBrowser() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    @AfterAll
    fun closeBrowser() {
        if (::browser.isInitialized) browser.close()
        if (::playwright.isInitialized) playwright.close()
    }

    @BeforeEach
    fun createContext() {
        context = browser.newContext(Browser.NewContextOptions().setIgnoreHTTPSErrors(true))
        context.setDefaultTimeout(DEFAULT_TIMEOUT_MS)
        context.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS)
        page = context.newPage()
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS)
        page.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS)
    }

    @AfterEach
    fun closeContext() {
        if (::context.isInitialized) context.close()
    }

    protected fun navigateWithRetry(
        url: String,
        attempts: Int = 3,
    ) {
        navigateWithRetry(attempts) { page.navigate(url) }
    }

    protected fun navigateWithRetry(
        attempts: Int = 3,
        navigate: () -> Unit,
    ) {
        PlaywrightNavigationRetries.retryOnConnectionRefused(attempts, navigate = navigate)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Double = 30_000.0
    }
}

internal object PlaywrightNavigationRetries {
    fun retryOnConnectionRefused(
        attempts: Int = 3,
        delayMillis: Long = 2_000,
        navigate: () -> Unit,
    ) {
        var lastException: PlaywrightException? = null
        repeat(attempts) { attempt ->
            try {
                navigate()
                return
            } catch (ex: PlaywrightException) {
                if (ex.message?.contains("ERR_CONNECTION_REFUSED") != true) {
                    throw ex
                }
                lastException = ex
                if (attempt < attempts - 1 && delayMillis > 0) {
                    Thread.sleep(delayMillis * (attempt + 1))
                }
            }
        }
        val failure = lastException ?: error("navigation failed without an exception")
        throw failure
    }
}
