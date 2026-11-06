// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup stream. The plaintext is split into chunks, each sealed with AES-GCM
 * under a nonce of a random prefix, the chunk index and a final-chunk flag, and authenticated
 * together with the header. Reordering, truncation or appended data fail authentication, and a
 * chunk is released only after its tag verifies, so large backups decrypt in bounded memory.
 */
object BackupCipher {
    class WrongPasswordException : IOException("BACKUP_WRONG_PASSWORD")
    class InvalidBackupException(message: String) : IOException(message)

    private val MAGIC = "DRDBAK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val TAG_BITS = 128
    internal const val CHUNK_BYTES = 1 shl 20

    fun encrypt(output: OutputStream, password: CharArray, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = header(ITERATIONS, salt, prefix)
        output.write(header)
        return Sealer(output, key(password, salt, ITERATIONS), prefix, header)
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { data.readFully(magic) } catch (_: EOFException) { throw InvalidBackupException("BACKUP_NOT_RECOGNIZED") }
        if (!magic.contentEquals(MAGIC)) throw InvalidBackupException("BACKUP_NOT_RECOGNIZED")
        val iterations = data.readInt()
        if (iterations !in 100_000..10_000_000) throw InvalidBackupException("BACKUP_NOT_RECOGNIZED")
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val prefix = ByteArray(PREFIX_BYTES).also(data::readFully)
        return Opener(data, key(password, salt, iterations), prefix, header(iterations, salt, prefix))
    }

    private fun header(iterations: Int, salt: ByteArray, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + PREFIX_BYTES)
            .put(MAGIC).putInt(iterations).put(salt).put(prefix).array()

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, last: Boolean, header: ByteArray): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
    }

    private class Sealer(
        output: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray
    ) : FilterOutputStream(output) {
        private val data = DataOutputStream(output)
        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed) { "Backup stream is closed" }
            var position = offset
            var remaining = length
            while (remaining > 0) {
                // A full chunk is sealed only once more data follows, so the last chunk carries the final flag.
                if (filled == CHUNK_BYTES) seal(last = false)
                val count = minOf(remaining, CHUNK_BYTES - filled)
                System.arraycopy(bytes, position, buffer, filled, count)
                filled += count; position += count; remaining -= count
            }
        }

        private fun seal(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, last, header).doFinal(buffer, 0, filled)
            data.writeInt(sealed.size)
            data.write(sealed)
            filled = 0
        }

        override fun flush() = data.flush()

        override fun close() {
            if (closed) return
            seal(last = true)
            closed = true
            data.flush()
            out.close()
        }
    }

    private class Opener(
        private val data: DataInputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var position = 0
        private var index = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position == chunk.size) {
                if (finished) return -1
                next()
            }
            val count = minOf(length, chunk.size - position)
            System.arraycopy(chunk, position, bytes, offset, count)
            position += count
            return count
        }

        private fun next() {
            val size = try { data.readInt() } catch (_: EOFException) { throw InvalidBackupException("BACKUP_TRUNCATED") }
            if (size !in 16..CHUNK_BYTES + 16) throw InvalidBackupException("BACKUP_CORRUPTED")
            val sealed = ByteArray(size)
            try { data.readFully(sealed) } catch (_: EOFException) { throw InvalidBackupException("BACKUP_TRUNCATED") }
            // The final flag is not stored; a chunk opens under exactly one of the two nonces.
            val opened = open(sealed, last = false) ?: open(sealed, last = true)?.also { finished = true }
                ?: if (index == 0) throw WrongPasswordException() else throw InvalidBackupException("BACKUP_CORRUPTED")
            if (finished && data.read() >= 0) throw InvalidBackupException("BACKUP_CORRUPTED")
            index++
            chunk = opened
            position = 0
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            cipher(Cipher.DECRYPT_MODE, key, prefix, index, last, header).doFinal(sealed)
        } catch (_: AEADBadTagException) { null }

        override fun close() = data.close()
    }
}
