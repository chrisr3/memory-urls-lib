package com.r3.sgx.memory

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.security.CodeSigner
import java.security.CodeSource
import java.security.SecureClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

open class MemoryClassLoader @Throws(IOException::class) constructor(
    urls: List<MemoryURL>, parent: ClassLoader?
) : SecureClassLoader(parent) {
    private companion object {
        private const val LOCAL_BLOCK_SIGNATURE = 0x04034b50
        private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
        private const val FLAGS_OFFSET = 6
        private const val COMPRESSED_SIZE_OFFSET = 18
        private const val BASE_DATA_DESCRIPTOR_SIZE = 12
        private val logger = LoggerFactory.getLogger(MemoryClassLoader::class.java)
    }

    @Suppress("unused")
    @Throws(IOException::class)
    constructor(urls: List<MemoryURL>) : this(urls, ClassLoader.getSystemClassLoader())

    private val tablesOfContents = urls.associateWith(::getTableOfContents)
    private val urls = ArrayList(urls)

    @Suppress("UsePropertyAccessSyntax")
    @Throws(IOException::class)
    private fun getTableOfContents(url: MemoryURL): Map<String, Int> {
        val connection = url.value.openConnection()
        return ZipInputStream(connection.getInputStream()).use { zip ->
            val cursor = (connection.content as ByteBuffer).duplicate().order(LITTLE_ENDIAN)
            val contents = linkedMapOf<String, Int>()
            while (true) {
                val entry = zip.nextEntry ?: break
                zip.closeEntry()

                val position = cursor.position()
                if (cursor.getInt(position) != LOCAL_BLOCK_SIGNATURE) {
                    throw ZipException("Incorrect computed position for ${url.value.path}!/${entry.name}")
                }

                val entryDataSize = entry.compressedSize
                if (entryDataSize > Int.MAX_VALUE) {
                    throw ZipException("Zip entry ${entry.name} has invalid size $entryDataSize")
                }

                if (contents.putIfAbsent(entry.name, position) != null) {
                    logger.warn("Duplicate entry {} in {}", entry.name, url.value)
                }

                val flagsField = cursor.getShort(position + FLAGS_OFFSET).toInt()
                cursor.position(position + COMPRESSED_SIZE_OFFSET)
                val compressedField = cursor.getInt()
                val uncompressedField = cursor.getInt()
                val nameLength = cursor.getShort().toUShort().toInt()
                val extraLength = cursor.getShort().toUShort().toInt()

                // Check that we're indexing the correct entry.
                val nameBytes = ByteArray(nameLength)
                cursor.get(nameBytes)
                val name = String(nameBytes, if (flagsField and 0x800 != 0) UTF_8 else US_ASCII)
                if (name != entry.name) {
                    throw ZipException("Expected ${entry.name} but found $name")
                }

                // Advance past all headers and the data itself.
                cursor.position(cursor.position() + extraLength + entryDataSize.toInt())

                // Check for a data descriptor trailing the data.
                if (flagsField and 0x08 != 0 && entry.size > 0) {
                    var dataDescriptorSize = BASE_DATA_DESCRIPTOR_SIZE
                    val current = cursor.position()
                    val signature = cursor.getInt(current)
                    if (signature == DATA_DESCRIPTOR_SIGNATURE) {
                        // This signature is optional.
                        dataDescriptorSize += Int.SIZE_BYTES
                    }
                    if (compressedField == -1 || uncompressedField == -1) {
                        // This is a Zip64 file entry.
                        dataDescriptorSize += (Long.SIZE_BYTES - Int.SIZE_BITS) * 2
                    }
                    cursor.position(current + dataDescriptorSize)
                }
            }
            contents
        }
    }

    fun getURLs(): Array<URL> {
        return urls.map(MemoryURL::value).toTypedArray()
    }

    private fun findResourceLocation(resourceName: String?): Pair<MemoryURL, Int>? {
        tablesOfContents.forEach { (host, tableOfContents) ->
            tableOfContents[resourceName]?.also { position ->
                return host to position
            }
        }
        return null
    }

    @Throws(ClassNotFoundException::class)
    final override fun findClass(name: String): Class<*>? {
        val resourceName = name.replace('.', '/') + ".class"
        findResourceLocation(resourceName)?.also { (host, position) ->
            val connection = host.value.openConnection()
            ZipInputStream(connection.getInputStream()).use { zip ->
                (connection.content as ByteBuffer).position(position)
                zip.nextEntry?.also { entry ->
                    if (resourceName == entry.name) {
                        val byteCode = ByteBuffer.wrap(zip.readBytes())
                        return defineClass(name, byteCode, host)
                    }
                }
            }
        }
        throw ClassNotFoundException(name)
    }

    private fun defineClass(name: String, byteCode: ByteBuffer, url: MemoryURL): Class<*>? {
        val idx = name.lastIndexOf('.')
        if (idx > 0) {
            val packageName = name.substring(0, idx)
            @Suppress("deprecation")
            if (getPackage(packageName) == null) {
                definePackage(packageName, null, null, null, null, null, null, null)
            }
        }
        return defineClass(name, byteCode, CodeSource(url.value, arrayOf<CodeSigner>()))
    }

    private fun createResource(hostURL: URL, resourceName: String?): URL {
        return URL(hostURL.protocol, null, -1, hostURL.path + "!/" + resourceName)
    }

    final override fun findResource(name: String?): URL? {
        return findResourceLocation(name)?.let { (host, _) ->
            createResource(host.value, name)
        }
    }

    final override fun findResources(name: String?): Enumeration<URL>? {
        return tablesOfContents.mapNotNull { (host, tableOfContents) ->
            host.takeIf { name in tableOfContents }?.let { createResource(it.value, name) }
        }.let(Collections::enumeration)
    }
}
