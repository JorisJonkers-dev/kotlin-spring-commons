package com.jorisjonkers.personalstack.common.test.system

import java.net.URI

data class StackTarget(
    val services: Map<String, URI>,
) {
    fun uriFor(service: String): URI =
        services[service] ?: error("No target URL configured for service: $service")

    fun urlFor(
        service: String,
        path: String = "",
    ): URI = uriFor(service).resolve(normalizePath(path))

    fun requireAll(services: Iterable<String>): StackTarget {
        val missing = services.filterNot(this.services::containsKey)
        require(missing.isEmpty()) {
            "Missing target URLs for services: ${missing.joinToString(", ")}"
        }
        return this
    }

    companion object {
        @JvmStatic
        fun fromEnvironment(
            serviceNames: Set<String> = emptySet(),
            targetListVariable: String = "STACK_TARGETS",
            properties: Map<String, String> = systemProperties(),
            environment: Map<String, String> = System.getenv(),
        ): StackTarget {
            val configured =
                parseTargetList(properties["test.targets"] ?: environment[targetListVariable].orEmpty()) +
                    serviceNames.mapNotNull { service ->
                        val value =
                            properties["test.$service.url"]
                                ?: environment["TEST_${service.envKey()}_URL"]
                        value?.takeIf(String::isNotBlank)?.let { service to URI.create(it) }
                    }

            return StackTarget(configured.toMap()).also { target ->
                target.requireAll(serviceNames)
            }
        }

        @JvmStatic
        fun parse(raw: String): StackTarget = StackTarget(parseTargetList(raw).toMap())

        private fun parseTargetList(raw: String): List<Pair<String, URI>> {
            if (raw.isBlank()) {
                return emptyList()
            }

            return raw
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .map { entry ->
                    val parts = entry.split("=", limit = 2)
                    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        "Invalid stack target entry: $entry"
                    }
                    parts[0] to URI.create(parts[1])
                }
        }

        private fun normalizePath(path: String): String =
            when {
                path.isBlank() -> ""
                path.startsWith("/") -> path
                else -> "/$path"
            }

        private fun String.envKey(): String =
            uppercase()
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith(System::getProperty)
    }
}
