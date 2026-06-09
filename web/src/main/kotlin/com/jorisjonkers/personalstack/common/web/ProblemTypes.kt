package com.jorisjonkers.personalstack.common.web

import java.net.URI

object ProblemTypes {
    @JvmStatic
    fun named(name: String): URI =
        URI.create("urn:problem-type:${name.trim().lowercase().replace('_', '-')}")
}
