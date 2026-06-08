package com.jorisjonkers.personalstack.common.command

interface CommandHandlerWithResult<T : Command, R> {
    fun handle(command: T): R
}
