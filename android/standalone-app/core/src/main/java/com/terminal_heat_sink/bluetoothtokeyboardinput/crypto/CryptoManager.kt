package com.terminal_heat_sink.bluetoothtokeyboardinput.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Re-implements the ECIES encryption scheme used by the desktop send_ble.py.
 *
 * Wire format for encrypted payloads:
 *   [32 bytes ephemeral pubkey][12 bytes nonce][ciphertext + 16 byte GCM tag]
 *
 * Inner plaintext:
 *   [4 bytes big-endian counter][actual plaintext]
 *
 * HMAC-wrapped writes (layout, OS, delay, provision) prepend:
 *   [32 bytes HMAC-SHA256(PSK, value)]
 */
class CryptoManager(private var psk: ByteArray) {
    private val random = SecureRandom()
    private var sendCounter = 0

    fun resetCounter() {
        sendCounter = 0
    }

    fun updatePsk(newPsk: ByteArray) {
        psk = newPsk.copyOf()
    }

    /**
     * Verifies that HMAC-SHA256(PSK, pubkeyBytes) == sigBytes.
     * Returns the pubkey bytes if valid, throws if not.
     */
    fun verifyPubkey(pubkeyBytes: ByteArray, sigBytes: ByteArray): ByteArray {
        require(pubkeyBytes.size == 32) { "Expected 32-byte public key, got ${pubkeyBytes.size}" }
        require(sigBytes.size == 32)    { "Expected 32-byte signature, got ${sigBytes.size}" }
        val expected = hmacSha256(psk, pubkeyBytes)
        // Constant-time comparison to resist timing attacks
        if (!expected.contentEquals(sigBytes)) {
            throw SecurityException(
                "Public key authentication failed — PSK mismatch or possible MITM attack."
            )
        }
        return pubkeyBytes
    }

    /**
     * ECIES-encrypt plaintext for the ESP32.
     * Packet = [32 eph_pub][12 nonce][ciphertext+16 tag]
     * with inner = [4-byte counter BE][plaintext]
     */
    fun encryptPayload(plaintext: ByteArray, esp32PubkeyBytes: ByteArray): ByteArray {
        sendCounter++
        val counterPrefix = ByteBuffer.allocate(4).putInt(sendCounter).array()
        val inner = counterPrefix + plaintext

        val ephPrivKey = X25519.generatePrivateKey()          // 32 bytes
        val ephPubKey  = X25519.publicFromPrivate(ephPrivKey) // 32 bytes
        val sharedSecret = X25519.computeSharedSecret(ephPrivKey, esp32PubkeyBytes)

        // HKDF-SHA256 with 32-zero-byte salt (RFC 5869 default) and empty info
        val salt = ByteArray(32)
        val aesKey = Hkdf.computeHkdf("HmacSha256", sharedSecret, salt, ByteArray(0), 32)

        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertextAndTag = cipher.doFinal(inner)

        return ephPubKey + nonce + ciphertextAndTag
    }

    /**
     * Prepends HMAC-SHA256(PSK, value) to value.
     * Used for layout, OS, delay, and provision characteristic writes.
     */
    fun hmacWrap(value: ByteArray): ByteArray {
        val tag = hmacSha256(psk, value)
        return tag + value
    }

    /**
     * Builds the 8-byte delay payload:
     *   [uint16 minHold BE][uint16 maxHold BE][uint16 minGap BE][uint16 maxGap BE]
     */
    fun buildDelayPayload(minHoldMs: Int, maxHoldMs: Int, minGapMs: Int, maxGapMs: Int): ByteArray {
        return ByteBuffer.allocate(8)
            .putShort(minHoldMs.toShort())
            .putShort(maxHoldMs.toShort())
            .putShort(minGapMs.toShort())
            .putShort(maxGapMs.toShort())
            .array()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
