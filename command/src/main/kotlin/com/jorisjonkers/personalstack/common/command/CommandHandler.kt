package com.jorisjonkers.personalstack.common.command

interface CommandHandler<T : Command> {
    fun handle(command: T)
}
