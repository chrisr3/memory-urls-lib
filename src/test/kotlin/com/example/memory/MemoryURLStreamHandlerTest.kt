package com.example.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.net.MalformedURLException
import java.nio.ByteBuffer

@ExtendWith(SecurityManagement::class)
class MemoryURLStreamHandlerTest {
    companion object {
        const val DATA_PATH: String = "/my/enclave"
        const val DATA: String = "Wibble!"
        const val EOF = -1
    }

    @AfterEach
    fun done() {
        URLSchemes.clearURLs()
    }

    @Test
    fun testMemoryURL() {
        val (url, urlLock) = URLSchemes.createMemoryURL(DATA_PATH, ByteBuffer.wrap(DATA.toByteArray()))
        assertEquals("memory:/my/enclave", url.toString())
        assertEquals("/my/enclave", url.path)
        assertEquals("/my/enclave", urlLock)
        assertNotSame(url.path, urlLock)
        url.openConnection().apply {
            assertEquals("application/octet-stream", contentType)
            assertEquals(DATA.length, contentLength)
            assertFalse(allowUserInteraction)
            assertFalse(doOutput)
            assertFalse(useCaches)
            assertTrue(doInput)
        }
    }

    @Test
    fun testExistingMemoryURL() {
        URLSchemes.createMemoryURL(DATA_PATH, ByteBuffer.wrap(byteArrayOf()))
        assertThrows<MalformedURLException> {
            URLSchemes.createMemoryURL(DATA_PATH, ByteBuffer.wrap(DATA.toByteArray()))
        }
    }

    @Test
    fun testReadOnlyMemoryBufferCannotBeModifiedAccidentally() {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + Int.SIZE_BYTES).let { buf ->
            buf.putInt(1).putInt(999).flip()
            buf.asReadOnlyBuffer()
        }
        assertTrue(buffer.isReadOnly)

        val (memoryURL, _) = URLSchemes.createMemoryURL(DATA_PATH, buffer)
        assertEquals(1, buffer.getInt())
        assertEquals(1, (memoryURL.content as ByteBuffer).getInt())
    }

    @Test
    fun testWritableMemoryBufferCannotBeModifiedAccidentally() {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + Int.SIZE_BYTES)
            .putInt(1).putInt(999)
        buffer.flip()
        assertFalse(buffer.isReadOnly)

        val (memoryURL, _) = URLSchemes.createMemoryURL(DATA_PATH, buffer)
        assertEquals(1, buffer.getInt())
        assertEquals(1, (memoryURL.content as ByteBuffer).getInt())
    }

    @Test
    fun testReadingEmptyBuffer() {
        val buffer = ByteBuffer.allocate(0)
        val (memoryURL, _) = URLSchemes.createMemoryURL(DATA_PATH, buffer)
        assertEquals(EOF, memoryURL.openStream().read())
        assertEquals(EOF, memoryURL.openStream().read(ByteArray(1)))
    }
}
