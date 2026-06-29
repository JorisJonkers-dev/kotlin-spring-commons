package com.jorisjonkers.personalstack.common.test.system

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class StackSmokeSuite(
    private val target: StackTarget,
    private val images: ImageTags = ImageTags.parse(""),
    private val checks: List<StackSmokeCheck> = target.services.keys.map { StackSmokeCheck(service = it) },
    private val requiredImageServices: Set<String> = emptySet(),
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    fun run(): StackSmokeResult {
        images.requireAll(requiredImageServices)

        val results = checks.map(::execute)
        val failures = results.filterNot(StackSmokeCheckResult::successful)
        check(failures.isEmpty()) {
            failures.joinToString(separator = "\n") {
                "${it.service} ${it.uri} returned ${it.statusCode ?: "no response"}${it.error?.let { error -> ": $error" } ?: ""}"
            }
        }
        return StackSmokeResult(results)
    }

    private fun execute(check: StackSmokeCheck): StackSmokeCheckResult {
        val uri = target.urlFor(check.service, check.path)
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(check.timeout)
                .GET()
                .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            StackSmokeCheckResult(
                service = check.service,
                uri = uri,
                statusCode = response.statusCode(),
                successful = response.statusCode() in check.expectedStatuses,
            )
        } catch (ex: Exception) {
            if (ex is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            StackSmokeCheckResult(
                service = check.service,
                uri = uri,
                statusCode = null,
                successful = false,
                error = ex.message,
            )
        }
    }
}

data class StackSmokeCheck(
    val service: String,
    val path: String = "/health",
    val expectedStatuses: IntRange = 200..399,
    val timeout: Duration = Duration.ofSeconds(10),
)

data class StackSmokeResult(
    val checks: List<StackSmokeCheckResult>,
)

data class StackSmokeCheckResult(
    val service: String,
    val uri: URI,
    val statusCode: Int?,
    val successful: Boolean,
    val error: String? = null,
)
