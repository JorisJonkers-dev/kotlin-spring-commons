package com.jorisjonkers.personalstack.common.test.system

class ImageTags private constructor(
    val tags: Map<String, String>,
) {
    fun tagFor(service: String): String? = tags[service] ?: System.getProperty("test.image.$service.tag")

    fun requireTagFor(service: String): String =
        tagFor(service) ?: error("No image tag configured for service: $service")

    fun requireAll(services: Iterable<String>): ImageTags {
        val missing = services.filter { tagFor(it).isNullOrBlank() }
        require(missing.isEmpty()) {
            "Missing image tags for services: ${missing.joinToString(", ")}"
        }
        return this
    }

    companion object {
        @JvmStatic
        fun fromEnvironment(
            variableName: String = "IMAGE_TAGS",
            allowedServices: Set<String> = emptySet(),
            serviceAliases: Map<String, String> = emptyMap(),
            properties: Map<String, String> = systemProperties(),
            environment: Map<String, String> = System.getenv(),
        ): ImageTags {
            val raw = properties["test.image-tags"] ?: environment[variableName].orEmpty()
            return parse(
                raw = raw,
                allowedServices = allowedServices,
                serviceAliases = serviceAliases,
            )
        }

        @JvmStatic
        fun parse(
            raw: String,
            allowedServices: Set<String> = emptySet(),
            serviceAliases: Map<String, String> = emptyMap(),
        ): ImageTags {
            if (raw.isBlank()) {
                return ImageTags(emptyMap())
            }

            val entries =
                raw
                    .trim()
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                    .mapNotNull { entry ->
                        parseEntry(
                            entry = entry,
                            allowedServices = allowedServices,
                            serviceAliases = serviceAliases,
                        )
                    }
            val duplicates = entries.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
            require(duplicates.isEmpty()) {
                "Duplicate IMAGE_TAGS services: ${duplicates.joinToString(", ")}"
            }

            return ImageTags(entries.toMap())
        }

        private fun parseEntry(
            entry: String,
            allowedServices: Set<String>,
            serviceAliases: Map<String, String>,
        ): Pair<String, String>? {
            val parts = entry.split("=", limit = 2)
            val explicitService = parts.size == 2
            val service =
                if (explicitService) {
                    parts[0]
                } else {
                    serviceFromImageRef(entry, serviceAliases)
                }
            val tag = if (explicitService) parts[1] else entry

            require(service.isNotBlank() && tag.isNotBlank()) {
                "Invalid IMAGE_TAGS entry: $entry"
            }
            require(explicitService || hasExplicitImageVersion(tag)) {
                "IMAGE_TAGS entry must use an explicit image tag or digest: $tag"
            }
            require(explicitService || !isLatestImageRef(tag)) {
                "IMAGE_TAGS entry must use an explicit non-latest tag: $tag"
            }
            if (allowedServices.isNotEmpty() && service !in allowedServices) {
                require(!explicitService) { "Unsupported IMAGE_TAGS service: $service" }
                return null
            }
            require(!isLatestImageRef(tag)) {
                "IMAGE_TAGS entry for $service must use an explicit non-latest tag"
            }
            return service to tag
        }

        private fun serviceFromImageRef(
            ref: String,
            serviceAliases: Map<String, String>,
        ): String {
            val imageName = ref.substringBefore("@").substringAfterLast("/").substringBefore(":")
            return serviceAliases[imageName] ?: imageName
        }

        private fun hasExplicitImageVersion(ref: String): Boolean {
            val imagePath = ref.substringBefore("@").substringAfterLast("/")
            return ref.contains("@sha256:") || imagePath.contains(":")
        }

        private fun isLatestImageRef(ref: String): Boolean {
            val imagePath = ref.substringBefore("@").substringAfterLast("/")
            val imageTag = imagePath.substringAfter(":", missingDelimiterValue = "")
            return ref == "latest" || imagePath == "latest" || imageTag == "latest"
        }

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith(System::getProperty)
    }
}
