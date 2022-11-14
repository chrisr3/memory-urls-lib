package com.r3.sgx.memory

import com.example.testing.ExampleTask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.Policy
import java.util.Collections.singletonList
import java.util.ServiceLoader
import java.util.function.Function

@ExtendWith(SecurityManagement::class)
class MemoryClassLoaderTest {
    companion object {
        const val DATA_PATH: String = "/my/enclave"

        private val exampleJar = DummyJar(Paths.get("build"), ExampleTask::class.java, "example")
            .withService("java.util.function.Function", ExampleTask::class.java)
            .build()
        private val logger = LoggerFactory.getLogger(MemoryClassLoaderTest::class.java)
    }

    private val queue = ReferenceQueue<URL>()
    private lateinit var memoryURL: MemoryURL

    @BeforeEach
    fun setup() {
        memoryURL = URLSchemes.createMemoryURL(DATA_PATH, ByteBuffer.wrap(
            Files.readAllBytes(exampleJar.path)
        ))
        System.gc()
    }

    @AfterEach
    fun done() {
        URLSchemes.clearURLs()
    }

    @Test
    fun testMemoryClass() {
        with(MemoryClassLoader(singletonList(memoryURL), null)) {
            val taskClass = Class.forName(ExampleTask::class.java.name, false, this)
            assertEquals(ExampleTask::class.java.name, taskClass.name)
            assertEquals(this, taskClass.classLoader)
            assertEquals(memoryURL.value, taskClass.protectionDomain.codeSource.location)

            with(taskClass.protectionDomain) {
                assertThat(permissions.elements().toList()).isEmpty()
                assertTrue(permissions.isReadOnly)
            }

            val taskClassResourceName = taskClass.resourceName
            val bytecodeURL = getResource(taskClassResourceName) ?: fail("Resource not found")
            assertEquals("${memoryURL.value}!/$taskClassResourceName", bytecodeURL.toString())

            @Suppress("unchecked_cast")
            val task = taskClass.getDeclaredConstructor().newInstance() as Function<String, String>
            logger.info("TASK>> {}", task.apply("Wibble!"))

            val permissions = Policy.getPolicy().getPermissions(taskClass.protectionDomain.codeSource)
            logger.info("PERMISSIONS: {}", permissions)

            val taskPackage = taskClass.`package`
            assertNotNull(taskPackage)
            assertEquals(ExampleTask::class.java.`package`.name, taskPackage.name)
            assertEquals(taskClass.name.removeSuffix(".ExampleTask"), taskPackage.name)
        }
    }

    @Test
    fun testMultipleResources() {
        val testingLibraries = (System.getProperty("testing-libraries.path") ?: fail("testing-libraries.path property not set"))
            .split(File.pathSeparator)
            .map { Paths.get(it) }
            .filter(Files::isReadable)
            .map(URLSchemes::createMemoryURL)

        MemoryClassLoader(testingLibraries, null).also { cl ->
            val manifests = cl.getResources("META-INF/MANIFEST.MF").toList()
            assertThat(manifests)
                .hasSameSizeAs(testingLibraries)
                .allMatch { url ->
                    url.protocol == "memory" && url.path.endsWith("!/META-INF/MANIFEST.MF")
                }
        }
    }

    @Test
    fun testMemoryClassLoader() {
        with(MemoryClassLoader(singletonList(memoryURL), null)) {
            assertEquals(1, getURLs().size)
            assertEquals("memory:$DATA_PATH", getURLs()[0].toString())

            val services = ServiceLoader.load(Function::class.java, this).toList()
            assertThat(services)
                .allMatch { it is Function }
                .hasSize(1)
        }
    }

    @Test
    fun testUnusedJarDataIsEvictedFromMemory() {
        val weakURL = createWeakReference("/tmp/data")
        System.gc()

        val ref = waitForReaping(10000)
        assertEquals(weakURL.path, ref.path)
    }

    @Test
    fun testWithURLClassLoader() {
        URLClassLoader(arrayOf(memoryURL.value), null).use { cl ->
            val taskClass = Class.forName(ExampleTask::class.java.name, false, cl)
            assertEquals(ExampleTask::class.java.name, taskClass.name)
            assertEquals(cl, taskClass.classLoader)
            assertEquals(memoryURL.value, taskClass.protectionDomain.codeSource.location)

            @Suppress("unchecked_cast")
            val task = taskClass.getDeclaredConstructor().newInstance() as Function<String, String>
            logger.info("TASK>> {}", task.apply("Wibble!"))
        }
    }

    @Suppress("SameParameterValue")
    private fun createWeakReference(path: String): URLReference {
        val localMemoryURL = URLSchemes.createMemoryURL(path, ByteBuffer.wrap(
            Files.readAllBytes(exampleJar.path)
        ))
        return URLReference(localMemoryURL.value, queue)
    }

    @Suppress("SameParameterValue")
    private fun waitForReaping(timeout: Long): URLReference {
        while(true) {
            val obj = queue.remove(timeout) ?: fail("Time up!")
            if (obj is URLReference) {
                return obj
            }
        }
    }

    class URLReference(url: URL, queue: ReferenceQueue<URL>) : WeakReference<URL>(url, queue) {
        val path: String = url.path
    }
}
