package com.jorisjonkers.personalstack.common.command

import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandBusTest {
    private data class TestCommand(
        val value: String,
    ) : Command

    @Test
    fun `command bus dispatches to handler`() {
        val handler = mockk<CommandHandler<TestCommand>>(relaxed = true)
        val command = TestCommand("test")

        handler.handle(command)

        verify { handler.handle(command) }
    }

    @Test
    fun `command interface is implemented by data class`() {
        val command: Command = TestCommand("hello")
        assertThat(command).isInstanceOf(Command::class.java)
    }
}
