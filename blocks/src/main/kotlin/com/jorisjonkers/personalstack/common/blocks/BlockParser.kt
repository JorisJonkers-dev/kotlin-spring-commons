package com.jorisjonkers.personalstack.common.blocks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Parses a stream of agent stdout into Blocks. The strategy is
 * intentionally dumb: scan for fenced regions tagged `block`, treat
 * each as JSON for a Block, and turn the surrounding plain text
 * into Text blocks. Anything that doesn't parse cleanly is
 * preserved as Text so we never silently drop output.
 */
class BlockParser(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val fence = Regex("```block\\s*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

    fun parse(stream: String): List<Block> {
        if (stream.isEmpty()) return emptyList()
        val result = mutableListOf<Block>()
        var cursor = 0
        for (m in fence.findAll(stream)) {
            val plain = stream.substring(cursor, m.range.first)
            if (plain.isNotBlank()) result.add(Block.Text(plain))
            val json = m.groupValues[1].trim()
            val block: Block =
                runCatching { mapper.readValue(json, Block::class.java) }
                    .getOrElse { Block.Text("[unparsed block: $json]") }
            result.add(block)
            cursor = m.range.last + 1
        }
        val trailing = stream.substring(cursor)
        if (trailing.isNotBlank()) result.add(Block.Text(trailing))
        return result
    }
}
