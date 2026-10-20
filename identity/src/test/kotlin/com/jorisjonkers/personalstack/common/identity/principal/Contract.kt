package com.jorisjonkers.personalstack.common.identity.principal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** The Principal contract scripts/pin-principal-contract.sh vendored into the test resources. */
object Contract {
    private const val ROOT = "/principal-contract/"
    private val mapper = ObjectMapper()

    fun text(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(ROOT + path)) { "no $path in the pinned contract" }
            .use { String(it.readAllBytes()) }

    fun json(path: String): JsonNode = mapper.readTree(text(path))

    /** Every vector of a channel, by name, read from the files the pinned contract lists. */
    fun vectors(channel: String): Map<String, JsonNode> =
        text("vectors/$channel/INDEX")
            .lines()
            .filter { it.isNotBlank() }
            .associateWith { json("vectors/$channel/$it.json") }
}
