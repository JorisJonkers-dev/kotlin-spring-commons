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

@ExtendWith(StackShardCondition::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PlaywrightStackTestBase {
    protected open val target: StackTarget = StackTarget.fromEnvironment()
    protected open val browserOptions: BrowserType.LaunchOptions =
        BrowserType.LaunchOptions().setHeadless(true)
    protected open val contextOptions: Browser.NewContextOptions =
        Browser.NewContextOptions().setIgnoreHTTPSErrors(true)
    protected open val defaultTimeoutMillis: Double = 30_000.0

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    protected lateinit var context: BrowserContext
    protected lateinit var page: Page

    @BeforeAll
    fun launchBrowser() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch(browserOptions)
    }

    @AfterAll
    fun closeBrowser() {
        if (::browser.isInitialized) browser.close()
        if (::playwright.isInitialized) playwright.close()
    }

    @BeforeEach
    fun createContext() {
        context = browser.newContext(contextOptions)
        context.setDefaultTimeout(defaultTimeoutMillis)
        context.setDefaultNavigationTimeout(defaultTimeoutMillis)
        page = context.newPage()
        page.setDefaultTimeout(defaultTimeoutMillis)
        page.setDefaultNavigationTimeout(defaultTimeoutMillis)
    }

    @AfterEach
    fun closeContext() {
        if (::context.isInitialized) context.close()
    }

    protected fun serviceUrl(
        service: String,
        path: String = "",
    ): String = target.urlFor(service, path).toString()

    protected fun navigateWithRetry(
        service: String,
        path: String = "",
        attempts: Int = 3,
    ) {
        navigateWithRetry(attempts) {
            page.navigate(serviceUrl(service, path))
        }
    }

    protected fun navigateWithRetry(
        url: String,
        attempts: Int = 3,
    ) {
        navigateWithRetry(attempts) {
            page.navigate(url)
        }
    }

    protected fun navigateWithRetry(
        attempts: Int = 3,
        delayMillis: Long = 2_000,
        navigate: () -> Unit,
    ) {
        require(attempts > 0) { "attempts must be positive" }
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
        throw lastException ?: IllegalStateException("navigation failed without an exception")
    }
}
