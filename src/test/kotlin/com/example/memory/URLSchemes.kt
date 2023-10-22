package com.example.memory

import java.net.MalformedURLException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.Policy

/**
 * Create and install a [MemoryURLStreamHandler] instance that we can test.
 */
object URLSchemes {
    private const val MEMORY_SCHEME = "memory"

    private val memory = MemoryURLStreamHandler(MEMORY_SCHEME)
    private val handlers = listOf(memory)
        .associateBy(MemoryURLStreamHandler::scheme)

    init {
        URL.setURLStreamHandlerFactory(handlers::get)

        // Switch to a security policy that uses the new URL scheme.
        System.setProperty("java.security.policy", "=${Paths.get("targeted.policy").toUri().toURL()}")
        Policy.getPolicy().refresh()
    }

    @Throws(MalformedURLException::class)
    fun createMemoryURL(path: String, data: ByteBuffer) = memory.createURL(path, data)

    @Throws(MalformedURLException::class)
    fun createMemoryURL(path: Path): MemoryURL {
        return createMemoryURL(
            path.toUri().toURL().path,
            ByteBuffer.wrap(Files.readAllBytes(path))
        )
    }

    val size get() = memory.size

    fun clearURLs() {
        memory.clear()
    }
}
