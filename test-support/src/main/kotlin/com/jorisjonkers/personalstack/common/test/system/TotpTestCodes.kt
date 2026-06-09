package com.jorisjonkers.personalstack.common.test.system

import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpTestCodes {
    private const val DEFAULT_STEP_SECONDS = 30L
    private const val DEFAULT_MIN_VALIDITY_SECONDS = 5L
    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".withIndex().associate { it.value to it.index }

    @JvmStatic
    fun generateFresh(
        secret: String,
        nowMillis: Long = System.currentTimeMillis(),
        digits: Int = 6,
        stepSeconds: Long = DEFAULT_STEP_SECONDS,
        minValiditySeconds: Long = DEFAULT_MIN_VALIDITY_SECONDS,
    ): String {
        val stepMillis = TimeUnit.SECONDS.toMillis(stepSeconds)
        val millisUntilNextStep = stepMillis - (nowMillis % stepMillis)
        if (millisUntilNextStep <= TimeUnit.SECONDS.toMillis(minValiditySeconds)) {
            Thread.sleep(millisUntilNextStep + 250)
        }
        return generate(secret, System.currentTimeMillis(), digits, stepSeconds)
    }

    @JvmStatic
    fun generate(
        secret: String,
        timeMillis: Long = System.currentTimeMillis(),
        digits: Int = 6,
        stepSeconds: Long = DEFAULT_STEP_SECONDS,
    ): String {
        require(digits in 6..8) { "digits must be between 6 and 8" }
        val counter = TimeUnit.MILLISECONDS.toSeconds(timeMillis) / stepSeconds
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val modulus = 10.0.pow(digits).toInt()
        return (binary % modulus).toString().padStart(digits, '0')
    }

    @JvmStatic
    fun decodeBase32(secret: String): ByteArray {
        var buffer = 0
        var bitsLeft = 0
        val output = ArrayList<Byte>()
        secret
            .uppercase(Locale.ROOT)
            .filterNot { it == '=' || it.isWhitespace() }
            .forEach { char ->
                val value = BASE32_ALPHABET[char] ?: error("Invalid Base32 character '$char'")
                buffer = (buffer shl 5) or value
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    output += ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                    bitsLeft -= 8
                }
            }
        return output.toByteArray()
    }
}
