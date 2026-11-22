package com.example.authentication

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * In-app PBKDF2-HMAC-SHA256 password hashing and constant-time verification
 * for local WiFiShare accounts stored in Room.
 */
object LocalPasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    data class HashedPassword(
        val saltHex: String,
        val hashHex: String
    )

    fun hashPassword(password: String): HashedPassword {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password.toCharArray(), salt)
        return HashedPassword(saltHex = salt.toHex(), hashHex = hash.toHex())
    }

    fun verifyPassword(password: String, saltHex: String, expectedHashHex: String): Boolean {
        val salt = hexToBytes(saltHex) ?: return false
        val expected = hexToBytes(expectedHashHex) ?: return false
        val actual = pbkdf2(password.toCharArray(), salt)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(chars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(chars, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { idx ->
                hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
