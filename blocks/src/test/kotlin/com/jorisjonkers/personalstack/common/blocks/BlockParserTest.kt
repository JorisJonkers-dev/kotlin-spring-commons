package com.jorisjonkers.personalstack.common.blocks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockParserTest {
    private val parser = BlockParser()

    @Test
    fun `plain text becomes one Text block`() {
        val blocks = parser.parse("hello world")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(Block.Text::class.java)
        assertThat((blocks[0] as Block.Text).md).isEqualTo("hello world")
    }

    @Test
    fun `fenced choice block parses with surrounding text`() {
        val raw =
            """
            before
            ```block
            {"kind":"choice","prompt":"approve?","options":[{"id":"y","label":"Yes"},{"id":"n","label":"No"}]}
            ```
            after
            """.trimIndent()
        val blocks = parser.parse(raw)
        assertThat(blocks).hasSize(3)
        assertThat(blocks[0]).isInstanceOf(Block.Text::class.java)
        assertThat(blocks[1]).isInstanceOf(Block.Choice::class.java)
        assertThat(blocks[2]).isInstanceOf(Block.Text::class.java)
        val choice = blocks[1] as Block.Choice
        assertThat(choice.prompt).isEqualTo("approve?")
        assertThat(choice.options).hasSize(2)
    }

    @Test
    fun `unparseable JSON inside block fence falls back to a Text marker`() {
        val raw = "```block\n{not json}\n```"
        val blocks = parser.parse(raw)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(Block.Text::class.java)
        assertThat((blocks[0] as Block.Text).md).contains("unparsed block")
    }

    @Test
    fun `multiple blocks are returned in order`() {
        val raw =
            """
            ```block
            {"kind":"text","md":"hi"}
            ```
            mid
            ```block
            {"kind":"diff","path":"a.txt","patch":"@@ -1 +1 @@\n-a\n+b"}
            ```
            """.trimIndent()
        val blocks = parser.parse(raw)
        assertThat(blocks).hasSize(3)
        assertThat(blocks[0]).isInstanceOf(Block.Text::class.java)
        assertThat(blocks[1]).isInstanceOf(Block.Text::class.java)
        assertThat(blocks[2]).isInstanceOf(Block.Diff::class.java)
    }
}
