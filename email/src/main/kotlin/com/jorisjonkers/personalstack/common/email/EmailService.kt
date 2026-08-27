package com.jorisjonkers.personalstack.common.email

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

// No @ConditionalOnBean here, deliberately.
//
// This class is picked up by component scanning, and @ConditionalOnBean is
// evaluated while the scan registers bean definitions -- which happens before
// auto-configuration runs. JavaMailSender comes from Spring Boot's
// MailSenderAutoConfiguration, so at evaluation time it does not exist yet, the
// condition is false, and EmailService is never registered. Spring Boot's own
// documentation says these annotations are only reliable on auto-configuration
// classes.
//
// The effect was silent: auth-api's listeners take Optional<EmailService>, found
// it empty, logged at DEBUG and returned. Registration confirmation and password
// reset mail were never sent and never errored -- no SMTP session ever reached
// the mail server.
//
// Without the condition, a consumer with no JavaMailSender fails to start with a
// missing-dependency error instead. That is the intended behaviour: a service
// that wires in the email module and cannot send email should say so loudly.
@Service
open class EmailService(
    private val mailSender: JavaMailSender,
    @param:Value("\${app.mail.from:noreply@example.test}")
    private val fromAddress: String,
    @param:Value("\${app.mail.from-name:Example Service}")
    private val fromName: String,
    @param:Value("\${app.mail.max-retries:3}")
    private val maxRetries: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    open fun send(request: EmailRequest) {
        var lastException: MailException? = null
        for (attempt in 1..maxRetries) {
            val failure = trySend(request, attempt) ?: return
            lastException = failure
        }
        // The exception flowing into this log is the *whole point* of
        // running on a background thread: without it, every SMTP-side
        // misconfiguration (AUTH 535, connection refused, TLS handshake,
        // expired credentials, missing principal …) is invisible.
        log.error(
            "Email delivery failed after {} attempts to={} subject=\"{}\"",
            maxRetries,
            request.to,
            request.subject,
            lastException,
        )
    }

    private fun trySend(
        request: EmailRequest,
        attempt: Int,
    ): MailException? =
        try {
            doSend(request)
            log.info("Email sent to={} subject=\"{}\" attempt={}/{}", request.to, request.subject, attempt, maxRetries)
            null
        } catch (e: MailException) {
            log.warn(
                "Email send failed to={} subject=\"{}\" attempt={}/{}",
                request.to,
                request.subject,
                attempt,
                maxRetries,
                e,
            )
            if (attempt < maxRetries) {
                Thread.sleep(attempt * RETRY_BACKOFF_MS)
            }
            e
        }

    private fun doSend(request: EmailRequest) {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(request.from ?: fromAddress, request.fromName ?: fromName)
        helper.setTo(request.to)
        helper.setSubject(request.subject)

        if (request.htmlBody != null) {
            helper.setText(request.textBody ?: stripHtml(request.htmlBody), request.htmlBody)
        } else {
            helper.setText(request.textBody.orEmpty())
        }

        request.replyTo?.let { helper.setReplyTo(it) }

        mailSender.send(message)
    }

    private fun stripHtml(html: String): String =
        html
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    companion object {
        private const val RETRY_BACKOFF_MS = 1000L
    }
}
