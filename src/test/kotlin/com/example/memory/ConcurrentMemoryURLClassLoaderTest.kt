package com.example.memory

import com.example.concurrent.ExampleCallable
import com.example.memory.DummyJar.Compression.BEST
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@Timeout(value = 60, unit = SECONDS)
class ConcurrentMemoryURLClassLoaderTest {
    private companion object {
        const val PAUSE_MILLIS = 100L
        const val MAX_THREADS = 10
        const val MAX_TASKS = 100

        private val logger = LoggerFactory.getLogger(ConcurrentMemoryURLClassLoaderTest::class.java)
        private val testingLibraries = (System.getProperty("testing-libraries.path") ?: fail("testing-libraries.path property not set"))
            .split(File.pathSeparator)
            .map { Paths.get(it) }
            .filter(Files::isReadable)
        private val concurrentJar = DummyJar(Paths.get("build"), ExampleCallable::class.java, "concurrent")
            .setCompression(BEST)
            .build()
    }

    private val executor = Executors.newFixedThreadPool(MAX_THREADS)

    @AfterEach
    fun done() {
        URLSchemes.clearURLs()
        executor.shutdownNow()
    }

    @Test
    fun testMultiThreaded() {
        val libraryURLs = testingLibraries.mapTo(ArrayList(), URLSchemes::createMemoryURL)
        libraryURLs += URLSchemes.createMemoryURL(concurrentJar.path)
        logger.info("Memory URLs created")

        val callables = mutableListOf<Callable<String>>()
        for (i in 0 until MAX_TASKS) {
            MemoryClassLoader(libraryURLs, null).let { cl ->
                @Suppress("unchecked_cast")
                val callableClass = Class.forName(ExampleCallable::class.java.name, false, cl) as Class<out Callable<String>>
                callables.add(callableClass.getConstructor(Int::class.javaPrimitiveType).newInstance(i))
            }
        }
        logger.info("Tasks created")

        val totalSize = URLSchemes.size
        assertEquals(libraryURLs.size, totalSize)

        val completor = ExecutorCompletionService<String>(executor)
        val futures = callables.mapTo(HashSet()) { callable ->
            completor.submit(callable)
        }
        logger.info("Tasks submitted")
        executor.shutdown()

        // Purge anything that is no longer strongly referenced.
        libraryURLs.clear()
        callables.clear()
        System.gc()

        assertEquals(totalSize, URLSchemes.size)

        while (futures.isNotEmpty()) {
            val result = completor.take()
            futures.remove(result)
            logger.info("Received: '{}'", result.get())
        }

        // Everything should now be garbage-collectable.
        System.gc()
        while (URLSchemes.size != 0) {
            Thread.sleep(PAUSE_MILLIS)
        }
    }
}
