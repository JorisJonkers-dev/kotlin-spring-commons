package com.jorisjonkers.personalstack.common.email

data class EmailRequest(
    val to: String,
    val subject: String,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    val replyTo: String? = null,
)
