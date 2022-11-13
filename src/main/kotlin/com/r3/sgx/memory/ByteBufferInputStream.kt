package com.r3.sgx.memory

import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * An implementation of [InputStream] that wraps a [ByteBuffer].
 * This is analogous to [ByteArrayInputStream][java.io.ByteArrayInputStream]
 * wrapping a [ByteArray].
 *
 * NOTE: Reading the [InputStream] will also modify the [ByteBuffer] position.
 */
class ByteBufferInputStream(private val bytes: ByteBuffer) : InputStream() {
    private companion object {
        private const val EOF = -1
    }

    override fun read(): Int {
        return if (bytes.hasRemaining()) {
            bytes.get().toInt()
        } else {
            EOF
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return if ((offset < 0) || (length < 0) || offset + length > buffer.size) {
            throw IndexOutOfBoundsException()
        } else {
            val bytesRemaining = bytes.remaining()
            if (bytesRemaining > 0) {
                val bytesRead = min(length, bytesRemaining)
                bytes.get(buffer, offset, bytesRead)
                bytesRead
            } else {
                EOF
            }
        }
    }

    override fun available(): Int = bytes.remaining()

    override fun markSupported(): Boolean = true

    override fun mark(offset: Int) {
        bytes.position(offset)
    }

    override fun reset() {
        bytes.reset()
    }
}
