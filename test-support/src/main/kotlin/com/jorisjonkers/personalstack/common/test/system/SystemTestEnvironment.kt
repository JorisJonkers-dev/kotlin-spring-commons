package com.jorisjonkers.personalstack.common.test.system

class SystemTestEnvironment(
    private val properties: Map<String, String> =
        System.getProperties().stringPropertyNames().associateWith(
            System::getProperty,
        ),
) {
    fun string(
        key: String,
        default: String,
    ): String = properties[key]?.takeIf { it.isNotBlank() } ?: default

    fun int(
        key: String,
        default: Int,
    ): Int = properties[key]?.toIntOrNull() ?: default

    fun optionalInt(key: String): Int? = properties[key]?.toIntOrNull()

    val apiUrl: String get() = string("test.api.url", "http://localhost:8080")
    val frontendUrl: String get() = string("test.frontend.url", "http://localhost:3000")
    val shardIndex: Int? get() = optionalInt("test.shard.index")
    val shardCount: Int? get() = optionalInt("test.shard.count")?.takeIf { it > 1 }

    companion object {
        @JvmField
        val current = SystemTestEnvironment()
    }
}
