package com.jorisjonkers.personalstack.common.test.system

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMultipart
import java.util.Properties

class StalwartMailClient(
    private val host: String = System.getProperty("test.imap.host", "localhost"),
    private val port: Int = System.getProperty("test.imap.port", "1143").toInt(),
    private val username: String = System.getProperty("test.imap.user", "bounce@dev.local"),
    private val password: String = System.getProperty("test.imap.password", "bounce"),
    private val folderName: String = System.getProperty("test.imap.folder", "INBOX"),
) {
    private val session: Session by lazy {
        Session.getInstance(
            Properties().apply {
                put("mail.store.protocol", "imap")
                put("mail.imap.host", host)
                put("mail.imap.port", port.toString())
                put("mail.imap.connectiontimeout", DEFAULT_TIMEOUT_MS.toString())
                put("mail.imap.timeout", DEFAULT_TIMEOUT_MS.toString())
                put("mail.imap.starttls.enable", "false")
                put("mail.imap.ssl.enable", "false")
            },
        )
    }

    fun reset() {
        withFolder(write = true) { folder ->
            val messages = folder.messages
            if (messages.isNotEmpty()) {
                folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            }
        }
    }

    fun findEmail(
        recipient: String,
        subject: String,
    ): DeliveredEmail? =
        withFolder(write = false) { folder ->
            folder.messages.firstOrNull { it.subject == subject && it.recipientsContains(recipient) }?.toDeliveredEmail()
        }

    fun assertEmailSent(
        recipient: String,
        subject: String,
        timeoutMs: Long = 10_000,
        pollIntervalMs: Long = 250,
    ): DeliveredEmail {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findEmail(recipient, subject)?.let { return it }
            Thread.sleep(pollIntervalMs)
        }
        throw AssertionError("Expected email subject=\"$subject\" recipient=$recipient within ${timeoutMs}ms but none arrived")
    }

    private fun <T> withFolder(
        write: Boolean,
        block: (Folder) -> T,
    ): T {
        val store = session.getStore("imap")
        store.connect(host, port, username, password)
        try {
            val folder = store.getFolder(folderName)
            folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            try {
                return block(folder)
            } finally {
                folder.close(write)
            }
        } finally {
            store.close()
        }
    }

    private fun Message.recipientsContains(recipient: String): Boolean {
        val all =
            (getRecipients(Message.RecipientType.TO) ?: emptyArray()) +
                (getRecipients(Message.RecipientType.CC) ?: emptyArray()) +
                (getRecipients(Message.RecipientType.BCC) ?: emptyArray())
        return all.any { (it as? InternetAddress)?.address?.equals(recipient, ignoreCase = true) == true }
    }

    private fun Message.toDeliveredEmail(): DeliveredEmail {
        val to = (getRecipients(Message.RecipientType.TO) ?: emptyArray()).mapNotNull { (it as? InternetAddress)?.address }
        val body =
            when (val content = content) {
                is String -> content
                is MimeMultipart -> extractText(content) ?: ""
                else -> content?.toString().orEmpty()
            }
        return DeliveredEmail(subject = subject ?: "", recipients = to, body = body)
    }

    private fun extractText(multipart: MimeMultipart): String? {
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            if (part.isMimeType("text/plain")) return part.content?.toString()
        }
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            if (part.isMimeType("text/html")) return part.content?.toString()
        }
        return null
    }

    data class DeliveredEmail(
        val subject: String,
        val recipients: List<String>,
        val body: String,
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 5_000
    }
}
