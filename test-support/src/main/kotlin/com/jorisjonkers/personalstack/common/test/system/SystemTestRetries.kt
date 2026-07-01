package com.jorisjonkers.personalstack.common.test.system

import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification
import java.net.ConnectException

object SystemTestRetries {
    @JvmStatic
    fun givenApi(): RequestSpecification = RestAssured.given().relaxedHTTPSValidation()

    @JvmStatic
    fun <T> retryOnConnectionFailure(
        attempts: Int = 3,
        delayMillis: Long = 2_000,
        action: () -> T,
    ): T {
        require(attempts > 0) { "attempts must be positive" }
        require(delayMillis >= 0) { "delayMillis must not be negative" }
        var lastException: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return action()
            } catch (ex: Exception) {
                if (!ex.hasCause<ConnectException>()) {
                    throw ex
                }
                lastException = ex
                if (attempt < attempts - 1 && delayMillis > 0) {
                    Thread.sleep(delayMillis * (attempt + 1))
                }
            }
        }
        val failure = lastException ?: error("retry failed without an exception")
        throw failure
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var cursor: Throwable? = this
        while (cursor != null) {
            if (cursor is T) return true
            cursor = cursor.cause
        }
        return false
    }
}
