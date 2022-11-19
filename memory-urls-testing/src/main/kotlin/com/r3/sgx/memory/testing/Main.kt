@file:JvmName("Main")
package com.r3.sgx.memory.testing

import com.r3.sgx.memory.MemoryClassLoader
import com.r3.sgx.memory.MemoryURL
import com.r3.sgx.memory.MemoryURLStreamHandler
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections.singletonList
import java.util.zip.ZipException
import kotlin.io.path.isReadable

private const val MEMORY_SCHEME = "memory"

private val logger = LoggerFactory.getLogger(MemoryClassLoader::class.java)
private val memory = MemoryURLStreamHandler(MEMORY_SCHEME)
private val handlers = listOf(memory).associateBy(MemoryURLStreamHandler::scheme)

@Throws(MalformedURLException::class)
private fun createMemoryURL(path: String, data: ByteBuffer) = memory.createURL(path, data)

@Throws(MalformedURLException::class)
private fun createMemoryURL(path: Path): MemoryURL {
    return createMemoryURL(
        path.toUri().toURL().path,
        ByteBuffer.wrap(Files.readAllBytes(path))
    )
}

private val Path.isJar: Boolean
    get() = isReadable() && fileName.toString().endsWith(".jar")

private fun verifyFile(jar: Path) {
    logger.info("Found: {}", jar)
    try {
        MemoryClassLoader(singletonList(createMemoryURL(jar)))
    } catch (e: ZipException) {
        logger.error("Failed to verify $jar", e)
    }
}

@Throws(IOException::class)
private fun verifyDirectory(dir: Path) {
    logger.info("Scanning: {}", dir)
    Files.walkFileTree(dir, object : FileVisitor<Path> {
        override fun preVisitDirectory(dir: Path, attr: BasicFileAttributes): FileVisitResult {
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attr: BasicFileAttributes): FileVisitResult {
            if (file.isJar) {
                verifyFile(file)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(path: Path?, ex: IOException?): FileVisitResult {
            logger.error("Visit failed for $path", ex)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path?, ex: IOException?): FileVisitResult {
            return if (ex != null) {
                logger.error("Failed to visit directory $dir", ex)
                FileVisitResult.TERMINATE
            } else {
                FileVisitResult.CONTINUE
            }
        }
    })
}

@Throws(IOException::class)
fun main(args: Array<String>) {
    URL.setURLStreamHandlerFactory(handlers::get)

    args.map { Paths.get(it).toAbsolutePath() }.forEach { path ->
        if (Files.isDirectory(path)) {
            verifyDirectory(path)
        } else if (path.isJar) {
            verifyFile(path)
        }
    }
}
