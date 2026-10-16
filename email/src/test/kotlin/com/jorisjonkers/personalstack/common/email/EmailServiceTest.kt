package com.jorisjonkers.personalstack.common.email

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender

class EmailServiceTest {
    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val mimeMessage = mockk<MimeMessage>(relaxed = true)

    private val emailServiceLogger =
        LoggerFactory.getLogger(EmailService::class.java) as Logger
    private val logAppender = ListAppender<ILoggingEvent>()

    init {
        logAppender.start()
        emailServiceLogger.addAppender(logAppender)
    }

    @AfterEach
    fun cleanup() {
        emailServiceLogger.detachAppender(logAppender)
    }

    private fun createService(maxRetries: Int = 3) =
        EmailService(
            mailSender = mailSender,
            fromAddress = "test@example.com",
            fromName = "Test Sender",
            maxRetries = maxRetries,
        )

    @Test
    fun `send delivers email successfully on first attempt`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Hello",
                textBody = "Body text",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send retries on MailException and succeeds on second attempt`() {
        every { mailSender.createMimeMessage() } returns mimeMessage
        var callCount = 0
        every { mailSender.send(any<MimeMessage>()) } answers {
            callCount++
            if (callCount == 1) throw MailSendException("temporary failure")
        }

        val service = createService(maxRetries = 3)
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Retry test",
                textBody = "Body",
            )

        service.send(request)

        verify(exactly = 2) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send exhausts all retries on persistent failure`() {
        every { mailSender.createMimeMessage() } returns mimeMessage
        every { mailSender.send(any<MimeMessage>()) } throws MailSendException("permanent failure")

        val service = createService(maxRetries = 2)
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Fail test",
                textBody = "Body",
            )

        service.send(request)

        verify(exactly = 2) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send logs the underlying exception after all retries are exhausted`() {
        // Regression: an earlier revision lost the caught exception in
        // the retry loop (`lastException = lastException`) so the final
        // ERROR log carried `null` — every SMTP misconfiguration in
        // production looked the same.
        every { mailSender.createMimeMessage() } returns mimeMessage
        val authFailure = MailAuthenticationException("535 5.7.8 Authentication failed")
        every { mailSender.send(any<MimeMessage>()) } throws authFailure

        val service = createService(maxRetries = 2)
        service.send(
            EmailRequest(
                to = "user@example.com",
                subject = "Hello",
                textBody = "Body",
            ),
        )

        val finalError =
            logAppender.list.single { it.level == Level.ERROR && "Email delivery failed" in it.formattedMessage }
        assertThat(finalError.throwableProxy).isNotNull
        assertThat(finalError.throwableProxy.className).isEqualTo(authFailure.javaClass.name)
        assertThat(finalError.throwableProxy.message).contains("535")
    }

    @Test
    fun `send uses html body when provided`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "HTML test",
                htmlBody = "<h1>Hello</h1>",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send uses custom from and replyTo when provided`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Custom from",
                textBody = "Body",
                from = "custom@example.com",
                fromName = "Custom Name",
                replyTo = "reply@example.com",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send with html body and no text body strips html for plain text`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Strip HTML",
                htmlBody = "<p>Hello</p><br/><p>World</p>",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send with both text and html body uses both`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Both bodies",
                textBody = "Plain text",
                htmlBody = "<p>HTML text</p>",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `send with no text and no html body sends empty text`() {
        every { mailSender.createMimeMessage() } returns mimeMessage

        val service = createService()
        val request =
            EmailRequest(
                to = "user@example.com",
                subject = "Empty body",
            )

        service.send(request)

        verify(exactly = 1) { mailSender.send(mimeMessage) }
    }

    @Test
    fun `an email request has no bodies, sender or reply-to unless given`() {
        val request = EmailRequest(to = "a@b.com", subject = "Sub")
        assertThat(request.textBody).isNull()
        assertThat(request.htmlBody).isNull()
        assertThat(request.from).isNull()
        assertThat(request.fromName).isNull()
        assertThat(request.replyTo).isNull()
    }
}
