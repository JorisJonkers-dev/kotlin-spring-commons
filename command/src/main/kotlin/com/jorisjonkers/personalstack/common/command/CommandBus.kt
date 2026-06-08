package com.jorisjonkers.personalstack.common.command

interface CommandBus {
    fun <T : Command> dispatch(command: T)
}
