package com.lightchat.core

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase encryption (AES-128 in counter mode, built from ECB blocks).
 * Stream layout of an encrypted message: [nonce 2 bytes][ciphertext, same length as the text bytes].
 * No bytes are added per chunk, so it costs only 2 bytes per message. Keystream is addressed by byte
 * position, so chunks can be decrypted as soon as they arrive (live) and in any order.
 *
 * This hides the content from someone who only watches the light; it does not authenticate the
 * sender (a wrong passphrase just gives garbage).
 */
object Crypt {
    private const val SALT = "LightChat-v1-salt"

    fun deriveKey(passphrase: String): ByteArray {
        // Hex of the UTF-8 bytes: any letters (Persian, English, ...), digits and symbols work, and
        // every PBKDF2 implementation (JVM, Android) treats the password the same way.
        val hex = StringBuilder()
        for (b in passphrase.toByteArray(Charsets.UTF_8)) hex.append(String.format("%02x", b.toInt() and 0xFF))
        val spec = PBEKeySpec(hex.toString().toCharArray(), SALT.toByteArray(Charsets.UTF_8), 20000, 128)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    /** XOR [data] (located at byte positions start.. of the cipher stream) with the keystream. */
    fun apply(key: ByteArray, nonce: Int, start: Int, data: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        var cachedBlock = -1
        var ks = ByteArray(16)
        for (i in data.indices) {
            val pos = start + i
            val block = pos / 16
            if (block != cachedBlock) {
                val ctr = ByteArray(16)
                ctr[0] = (nonce ushr 8).toByte()
                ctr[1] = nonce.toByte()
                ctr[12] = (block ushr 24).toByte()
                ctr[13] = (block ushr 16).toByte()
                ctr[14] = (block ushr 8).toByte()
                ctr[15] = block.toByte()
                ks = aes.doFinal(ctr)
                cachedBlock = block
            }
            out[i] = (data[i].toInt() xor ks[pos % 16].toInt()).toByte()
        }
        return out
    }
}
